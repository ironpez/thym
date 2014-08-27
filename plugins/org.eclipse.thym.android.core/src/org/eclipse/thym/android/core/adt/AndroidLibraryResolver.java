/*******************************************************************************
 * Copyright (c) 2013, 2014 Red Hat, Inc. 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * 	Contributors:
 * 		 Red Hat Inc. - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.thym.android.core.adt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.eclipse.thym.android.core.AndroidCore;
import org.eclipse.thym.core.HybridCore;
import org.eclipse.thym.core.HybridMobileStatus;
import org.eclipse.thym.core.engine.HybridMobileLibraryResolver;
import org.eclipse.thym.core.internal.util.FileUtils;

public class AndroidLibraryResolver extends
		HybridMobileLibraryResolver {

	public static final String DIR_LIBS = "libs";
	public static final String DIR_RES = "res";
	public static final String DIR_SRC = "src";
	
	public static final String FILE_JAR_CORDOVA = "cordova.jar";
	private static final IPath KEY_PATH_CORDOVA_JAR = new Path(DIR_LIBS +"/" + FILE_JAR_CORDOVA);
	public static final String FILE_XML_ANDROIDMANIFEST = "AndroidManifest.xml";

	HashMap<IPath, URL> files = new HashMap<IPath, URL>();
	
	private void initFiles() {
		Assert.isNotNull(libraryRoot, "Library resolver is not initialized. Call init before accessing any other functions.");
		if(version == null ){
			return;
		}
		IPath templatePrjRoot = libraryRoot.append("bin/templates/project");
		IPath cordovaJar = libraryRoot.append("framework").append(NLS.bind("cordova-{0}.jar",version));
		files.put(KEY_PATH_CORDOVA_JAR, getEngineFile(cordovaJar));	
		files.put(new Path(DIR_RES),getEngineFile(templatePrjRoot.append(DIR_RES)));
		files.put(new Path(FILE_XML_ANDROIDMANIFEST), getEngineFile(templatePrjRoot.append(FILE_XML_ANDROIDMANIFEST)));
		files.put(new Path(DIR_SRC).append(VAR_PACKAGE_NAME.replace('.', '/')).append(VAR_APP_NAME+".java"), 
				getEngineFile(templatePrjRoot.append("Activity.java")));
		files.put(PATH_CORDOVA_JS, getEngineFile(libraryRoot.append("framework/assets/www/cordova.js")));
		
	}


	@Override
	public URL getTemplateFile(IPath destination) {
		if(files.isEmpty()) initFiles();
		Assert.isNotNull(destination);
		Assert.isTrue(!destination.isAbsolute());
		return files.get(destination);
	}

	@Override
	public IStatus isLibraryConsistent() {
		if(version == null ){
			return new Status(IStatus.ERROR, HybridCore.PLUGIN_ID, "Library for Android platform is not compatible with this tool. File for path {0} is missing.");
		}
		if(files.isEmpty()) initFiles();
		Iterator<IPath> paths = files.keySet().iterator();
		while (paths.hasNext()) {
			IPath key = paths.next();
			URL url = files.get(key);
			if(url != null  ){
				File file = new File(url.getFile());
				if( file.exists() || key.equals(KEY_PATH_CORDOVA_JAR)){
					// we skip cordova.jar because it is generated by precompilation 
					continue;
				}
			}
			return new Status(IStatus.ERROR, HybridCore.PLUGIN_ID, NLS.bind("Library for Android platform is not compatible with this tool. File for path {0} is missing.",key.toString()));
		}
		return Status.OK_STATUS;
	}
	
	public void preCompile(IProgressMonitor monitor) throws CoreException{
		File projectDir = libraryRoot.append("framework").toFile();
		if(!projectDir.isDirectory()){
			throw new CoreException(HybridMobileStatus.newMissingEngineStatus(null, "Library for the Active Hybrid Mobile Engine for Android is incomplete. No framework directory is present."));
		}
		AndroidSDK sdk = getLibraryTarget();
		AndroidSDKManager sdkManager = AndroidSDKManager.getManager();
		sdkManager.updateProject(sdk, null, true, projectDir,monitor);
		BuildDelegate buildDelegate = new BuildDelegate();
		if(monitor.isCanceled())
			return;
		buildDelegate.buildLibraryProject(projectDir, monitor);
	}
	
	public boolean needsPreCompilation(){
		IPath cordovaJar = libraryRoot.append("framework").append(NLS.bind("cordova-{0}.jar",version));
		return !cordovaJar.toFile().exists();
	}
	
	private AndroidSDK getLibraryTarget() throws CoreException{
		File projProps = libraryRoot.append("framework").append("project.properties").toFile();

		try {
			FileReader reader = new FileReader(projProps);
			Properties props = new Properties();
			props.load(reader);
			String targetValue = props.getProperty("target");
			int splitIndex = targetValue.indexOf('-');
			if(targetValue != null && splitIndex >-1){
				AndroidAPILevelComparator alc = new AndroidAPILevelComparator();
				targetValue = targetValue.substring(splitIndex+1);
				AndroidSDKManager sdkManager = AndroidSDKManager.getManager();
				List<AndroidSDK> targets = sdkManager.listTargets();
				for (AndroidSDK androidSDK : targets) {
					if(alc.compare(targetValue, androidSDK.getApiLevel())==0){
						return androidSDK;
					}
				}

			}
		} catch (FileNotFoundException e) {
			AndroidCore.log(IStatus.WARNING, "Missing project.properties for library", e);
		} catch (IOException e) {
			AndroidCore.log(IStatus.WARNING, "Failed to read target API level from library", e);
		}
		return  AndroidProjectUtils.selectBestValidTarget();
	}

	private URL getEngineFile(IPath path){
		File file = path.toFile();
		if(!file.exists()){
			HybridCore.log(IStatus.ERROR, "missing Android engine file " + file.toString(), null );
		}
		return FileUtils.toURL(file);
	}


	@Override
	public String detectVersion() {
		File versionFile = this.libraryRoot.append("VERSION").toFile();
		if(versionFile.exists()){
			BufferedReader reader = null;
			try{
				try {
					reader = new BufferedReader(new FileReader(versionFile));
					String version = reader.readLine();
					return version;
				} 
				finally{
					if(reader != null ) reader.close();
				}
			}catch (IOException e) {
				AndroidCore.log(IStatus.ERROR, "Can not detect version on library", e);
			}
		}else{
			AndroidCore.log(IStatus.ERROR, NLS.bind("Can not detect version. VERSION file {0} is missing",versionFile.toString()), null);
		}
		return null;
	}

}
