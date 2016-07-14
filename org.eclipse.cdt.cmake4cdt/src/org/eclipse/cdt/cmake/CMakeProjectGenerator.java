/*******************************************************************************
 * Copyright (c) 2014 Rohde & Schwarz GmbH & Co. KG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     This class was heavily quoted from org.eclipse.cdt.arduino.core.ArduinoProjectGenerator
 *     Martin Runge - initial implementation of cmake support
 *******************************************************************************/

package org.eclipse.cdt.cmake;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.cdt.cmake.ui.Messages;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.extension.CConfigurationData;
import org.eclipse.core.commands.ExecutionException;

import org.eclipse.core.resources.ICommand;
import org.eclipse.cdt.managedbuilder.core.IToolChain;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.cdt.managedbuilder.internal.core.ManagedBuildInfo;
import org.eclipse.cdt.managedbuilder.internal.core.ManagedProject;
import org.eclipse.cdt.managedbuilder.internal.core.ToolChain;
import org.eclipse.cdt.utils.Platform;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ide.undo.CreateProjectOperation;
import org.eclipse.ui.ide.undo.WorkspaceUndoUtil;
import org.eclipse.ui.internal.ide.IDEWorkbenchPlugin;
import org.eclipse.ui.internal.ide.StatusUtil;
import org.eclipse.ui.internal.wizards.newresource.ResourceMessages;
import org.eclipse.ui.statushandlers.StatusAdapter;
import org.eclipse.ui.statushandlers.StatusManager;

import freemarker.core.ParseException;
import freemarker.template.Configuration;
import freemarker.template.MalformedTemplateNameException;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateNotFoundException;

/**
 * @author runge_m
 *
 */
public class CMakeProjectGenerator {
 
	public static final String CMAKE_TOOLCHAIN_ID = "org.eclipse.cdt.cmake.toolChain.debug";
	public static final String CMAKE_BUILDER_ID = "org.eclipse.cdt.cmake.CMakeProjectBuilder";
	public static final String GEN_MAKE_BUILDER_ID = "org.eclipse.cdt.managedbuilder.core.genmakebuilder";
	
	private IProject project;
	
    private IWizardContainer container = null;
	/**
	 * @param newProject
	 */
	public CMakeProjectGenerator(IProject newProject) {
		project = newProject;
	}

	/**
	 * @param monitor
	 */
	public void setupProject(IProgressMonitor monitor) throws CoreException {
		// Add CMake nature
		IProjectDescription projDesc = project.getDescription();
		boolean cmakeNatureAlreadyThere = false;
		String[] oldIds = projDesc.getNatureIds();

		for(int i=0; i < oldIds.length; i++) {
			if(oldIds[i].equals(CMakeProjectNature.CMAKE_NATURE_ID)) {
				cmakeNatureAlreadyThere = true;
			}
		}
		if(!cmakeNatureAlreadyThere) {
			String[] newIds = new String[oldIds.length + 1];
			System.arraycopy(oldIds, 0, newIds, 0, oldIds.length);
			newIds[newIds.length - 1] = CMakeProjectNature.CMAKE_NATURE_ID;
			projDesc.setNatureIds(newIds);
			project.setDescription(projDesc, monitor);
		}

		// create the CDT natures and build setup
		CCorePlugin.getDefault().createCDTProject(projDesc, project, monitor);
		CCorePlugin.getDefault().convertProjectFromCtoCC(project, monitor);
		
		ICProjectDescription cprojDesc = CCorePlugin.getDefault().createProjectDescription(project, false);
		ManagedBuildInfo info = ManagedBuildManager.createBuildInfo(project);
		ManagedProject mProj = new ManagedProject(cprojDesc);
		info.setManagedProject(mProj);

		createBuildConfigurations(cprojDesc);

		// Add CMake builder
		projDesc = project.getDescription();
		ICommand[] oldBuilders = projDesc.getBuildSpec();

		boolean genMakeBilderAlreadyThere = false;
		boolean cmakeBuilderAlreadyThere = false;
		int additionalBuilders = 2;
		for(int i=0; i < oldBuilders.length; i++) {
			if(oldBuilders[i].getBuilderName().equals(CMAKE_BUILDER_ID)) {
				cmakeBuilderAlreadyThere = true;
				additionalBuilders--;
			}
			if(oldBuilders[i].getBuilderName().equals(GEN_MAKE_BUILDER_ID)) {
				genMakeBilderAlreadyThere = true;
				additionalBuilders--;
			}
		}
		if(additionalBuilders < 0) {
			additionalBuilders = 0;
		}

		ICommand[] newBuilders = new ICommand[oldBuilders.length + additionalBuilders];
		
		if(!cmakeBuilderAlreadyThere) {
			ICommand cmakeBuilder = projDesc.newCommand();
			cmakeBuilder.setBuilderName(CMAKE_BUILDER_ID);
			cmakeBuilder.setBuilding(IncrementalProjectBuilder.FULL_BUILD, true);
			cmakeBuilder.setBuilding(IncrementalProjectBuilder.INCREMENTAL_BUILD, true);
			cmakeBuilder.setBuilding(IncrementalProjectBuilder.CLEAN_BUILD, true);
			newBuilders[0] = cmakeBuilder;
		}
		
		if(!genMakeBilderAlreadyThere) {
			ICommand makeBuilder = projDesc.newCommand();
			makeBuilder.setBuilderName(GEN_MAKE_BUILDER_ID);
			makeBuilder.setBuilding(IncrementalProjectBuilder.FULL_BUILD, true);
			makeBuilder.setBuilding(IncrementalProjectBuilder.INCREMENTAL_BUILD, true);
			makeBuilder.setBuilding(IncrementalProjectBuilder.CLEAN_BUILD, true);
			newBuilders[1] = makeBuilder;
		}		
		System.arraycopy(oldBuilders, 0, newBuilders, additionalBuilders, oldBuilders.length);
		
		projDesc.setBuildSpec(newBuilders);
		project.setDescription(projDesc, monitor);		

		CCorePlugin.getDefault().setProjectDescription(project, cprojDesc, true, monitor);
	}

	public void extractHelloWorldTemplate(IProgressMonitor monitor) throws CoreException {
		// Generate files
		try {
			Configuration fmConfig = new Configuration(Configuration.VERSION_2_3_22);
			URL templateDirURL = FileLocator.find(Activator.getContext().getBundle(), new Path("/templates/ConsoleHelloWorld"), null); //$NON-NLS-1$

			// workaround bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=145096  (spaces in URL not escaped for URI)
			URL tmpURL = FileLocator.toFileURL(templateDirURL);
			URI tmpURI = new URI(tmpURL.getProtocol(), tmpURL.getPath(), null);
			fmConfig.setDirectoryForTemplateLoading(new File(tmpURI));

			final Map<String, Object> fmModel = new HashMap<>();
			fmModel.put("projectName", project.getName()); //$NON-NLS-1$

			instantiateTemplate( fmModel, fmConfig );
			//generateFile(fmModel, fmConfig.getTemplate("Makefile"), project.getFile("Makefile")); //$NON-NLS-1$ //$NON-NLS-2$
			//generateFile(fmModel, fmConfig.getTemplate("arduino.mk"), project.getFile("arduino.mk")); //$NON-NLS-1$ //$NON-NLS-2$

			// sourceFile = project.getFile(project.getName() + ".cpp"); //$NON-NLS-1$
			// generateFile(fmModel, fmConfig.getTemplate("arduino.cpp"), sourceFile); //$NON-NLS-1$
		} catch (IOException e) {
			throw new CoreException(new Status(IStatus.ERROR, Activator.getId(), e.getLocalizedMessage(), e));
		} catch (URISyntaxException e) {
			throw new CoreException(new Status(IStatus.ERROR, Activator.getId(), e.getLocalizedMessage(), e));
    	} catch (TemplateException e) {
    		throw new CoreException(new Status(IStatus.ERROR, Activator.getId(), e.getLocalizedMessage(), e));
		}

	}

	/**
	 * @param cprojDesc
	 * @return 
	 */
	private ICConfigurationDescription createBuildConfigurations(ICProjectDescription cprojDesc) throws CoreException {

		ManagedProject managedProject = new ManagedProject(cprojDesc);
		String configId = ManagedBuildManager.calculateChildId(CMAKE_TOOLCHAIN_ID, null);
		IToolChain cmakeToolChain = ManagedBuildManager.getExtensionToolChain(CMAKE_TOOLCHAIN_ID);
		org.eclipse.cdt.managedbuilder.internal.core.Configuration newConfig = new org.eclipse.cdt.managedbuilder.internal.core.Configuration(
				managedProject, (ToolChain) cmakeToolChain, configId, "debug");
		IToolChain newToolChain = newConfig.getToolChain();

		switch (Platform.getOS()) 
		{
			case "win32": {
				newConfig.getEditableBuilder().setCommand("mingw32-make.exe");
				break;
			}
			default: {
				newConfig.getEditableBuilder().setCommand("make");
				break;
			}
		}

		
		CConfigurationData data = newConfig.getConfigurationData();
		return cprojDesc.createConfiguration(ManagedBuildManager.CFG_DATA_PROVIDER_ID, data);
	}

	
	/**
	 * @param fmModel
	 * @param fmConfig
	 * @throws CoreException 
	 * @throws IOException 
	 * @throws TemplateException 
	 * @throws ParseException 
	 * @throws MalformedTemplateNameException 
	 * @throws TemplateNotFoundException 
	 */
	private void instantiateTemplate(Map<String, Object> fmModel, Configuration fmConfig) throws TemplateNotFoundException, MalformedTemplateNameException, ParseException, TemplateException, IOException, CoreException {
		generateFile(fmModel, fmConfig.getTemplate("CMakeLists.txt"), project.getFile("CMakeLists.txt"));
		generateFile(fmModel, fmConfig.getTemplate("main.cpp"), project.getFile("main.cpp"));
	
	}
	
	private static void generateFile(Object model, Template template, final IFile outputFile)
			throws TemplateException, IOException, CoreException {
		final PipedInputStream in = new PipedInputStream();
		PipedOutputStream out = new PipedOutputStream(in);
		final Writer writer = new OutputStreamWriter(out);
		Job job = new Job(Messages.CMakeProjectGenerator_0) {
			protected IStatus run(IProgressMonitor monitor) {
				try {
					outputFile.create(in, true, monitor);
				} catch (CoreException e) {
					return e.getStatus();
				}
				return Status.OK_STATUS;
			}
		};
		job.setRule(outputFile.getProject());
		job.schedule();
		template.process(model, writer);
		writer.close();
		try {
			job.join();
		} catch (InterruptedException e) {
			// TODO anything?
		}
		IStatus status = job.getResult();
		if (!status.isOK())
			throw new CoreException(status);

	}

	/**
	 * @return
	 */
	public IFile getSourceFile() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @param shell 
	 * @return 
	 * 
	 */
	public IProject createProject(IWizard parent, URI locationURI) {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IWizard containerRef = parent;
		final IProjectDescription description = workspace.newProjectDescription(project.getName());
		description.setLocationURI(locationURI);

		// create the new project operation
		IRunnableWithProgress op = new IRunnableWithProgress() {
			@Override
			public void run(IProgressMonitor monitor)
					throws InvocationTargetException {
				CreateProjectOperation op = new CreateProjectOperation(description, ResourceMessages.NewProject_windowTitle);
				try {
					// see bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=219901
					// directly execute the operation so that the undo state is
					// not preserved.  Making this undoable resulted in too many
					// accidental file deletions.
					op.execute(monitor, WorkspaceUndoUtil.getUIInfoAdapter(containerRef.getContainer().getShell()));
				} catch ( org.eclipse.core.commands.ExecutionException e) {
					throw new InvocationTargetException(e);
				}
			}
		};

		// run the new project creation operation
		try {
			parent.getContainer().run(true, true, op);
		} catch (InterruptedException e) {
			return null;
		} catch (InvocationTargetException e) {
			Throwable t = e.getTargetException();
			if (t instanceof ExecutionException
					&& t.getCause() instanceof CoreException) {
				CoreException cause = (CoreException) t.getCause();
				StatusAdapter status;
				if (cause.getStatus().getCode() == IResourceStatus.CASE_VARIANT_EXISTS) {
					status = new StatusAdapter(
							StatusUtil
									.newStatus(
											IStatus.WARNING,
											NLS
													.bind(
															ResourceMessages.NewProject_caseVariantExistsError,
															project.getName()),
											cause));
				} else {
					status = new StatusAdapter(StatusUtil.newStatus(cause
							.getStatus().getSeverity(),
							ResourceMessages.NewProject_errorMessage, cause));
				}
				status.setProperty(StatusAdapter.TITLE_PROPERTY,
						ResourceMessages.NewProject_errorMessage);
				StatusManager.getManager().handle(status, StatusManager.BLOCK);
			} else {
				StatusAdapter status = new StatusAdapter(new Status(
						IStatus.WARNING, IDEWorkbenchPlugin.IDE_WORKBENCH, 0,
						NLS.bind(ResourceMessages.NewProject_internalError, t
								.getMessage()), t));
				status.setProperty(StatusAdapter.TITLE_PROPERTY,
						ResourceMessages.NewProject_errorMessage);
				StatusManager.getManager().handle(status,
						StatusManager.LOG | StatusManager.BLOCK);
			}
			return null;
		}

		return project;
		
	}
	
	
	

}
