package thinwire.tools.jso;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;

/**
 * 
 * @goal jso
 * 
 * @phase process-sources
 */
public class MyMojo extends AbstractMojo {
    /**
     * @parameter
     * @required
     */
    private File srcdir;
    /**
     * @parameter
     * @required
     */
    private File destdir;
    /**
     * @parameter
     * @required
     */
    private String namemap;
    
    /**
     * @parameter default-value=true
     */
    private boolean verify;
    
    /**
     * @parameter
     */
    private boolean compress;

    public void execute() throws MojoExecutionException {
        if (srcdir == null) throw new MojoExecutionException("srcdir must be specified");
        if (destdir == null) throw new MojoExecutionException("destdir must be specified");
        if (namemap == null) throw new MojoExecutionException("namemap must be specified");
        float beforeTotal = 0;
        float afterTotal = 0;
        float compressTotal = 0;
        
        List<File> lst = new ArrayList<File>();
        
        try {
        
            for (File f : srcdir.listFiles()) {
                if (f.isFile() && f.getName().endsWith(".js")) {
                    lst.add(f);
                }
            }
            
            
            log("Processing " + lst.size() + " file(s) collectively");
            log("Source directory is " + srcdir.getCanonicalPath());
            log("Destination directory is " + destdir.getCanonicalPath());
            if (compress) log("Compression is turned on");
            
          //Compile and obfuscate the files
            Optimizer jso = new Optimizer();
            log("Anaylizing name patterns...");
            Context context = Context.enter();
            
            for (File f : lst) {
                Script script = context.compileReader(new InputStreamReader(new FileInputStream(f)), f.getCanonicalPath(), 1, null);
                jso.analyzeNames(script);
            }
            
            byte[] nmAry = jso.getNameMapScript().getBytes();
            log("Generated name map of size " + nmAry.length + " bytes");
            afterTotal += nmAry.length;
            File fNameMap = new File(destdir, getDestPath(new File(srcdir, namemap)));
            ByteArrayOutputStream baosNameMap = new ByteArrayOutputStream();
            OutputStream osNameMap = baosNameMap;
            if (compress) osNameMap = new GZIPOutputStream(osNameMap);
            osNameMap.write(nmAry);
            
            log("Generating optimized scripts...");
            
            for (File f : lst) {
                InputStreamReader isr = new InputStreamReader(new FileInputStream(f));
                Script script = context.compileReader(isr, f.getCanonicalPath(), 1, null);
                byte[] ary = jso.generate(script).getBytes();
                isr.close();
                
                String destPath = getDestPath(f);
                File destFile = new File(destdir, destPath);
                
                float before = (float)f.length();
                beforeTotal += before;
                afterTotal += ary.length;
                log("Optimized file '" + destPath + "' from " + (int)before + " to " + ary.length + " bytes, " + ((10000 - Math.round((ary.length / before) * 10000)) / 100) + "% reduction");
                
                if (destFile.equals(fNameMap)) {
                    log("Attaching name map to beginning of '" + destPath + "'");
                    osNameMap.write(ary);
                    //fNameMap = null;
                } else {
                    destFile.mkdirs();

                    if (destFile.exists()) {
                        destFile.delete();
                        destFile = new File(destFile.getAbsolutePath());
                        log("destFile=" + destFile.getCanonicalPath() + ",exists=" + destFile.exists());
                    }
                    
                    OutputStream fos = new FileOutputStream(destFile);
                    if (compress) fos = new GZIPOutputStream(fos);
                    fos.write(ary);
                    fos.close();
                    if (compress) compressTotal += new File(destdir, f.getName()).length();
                }
                
                if (verify) {
                    log("Verifying file '" + destPath + "' for syntactic accuracy");
                    context.compileReader(new InputStreamReader(new ByteArrayInputStream(ary)), destFile.getCanonicalPath(), 1, null);
                }
            }
            
            Context.exit();
            fNameMap.mkdirs();
            
            if (fNameMap.exists()) {
                fNameMap.delete();
                fNameMap = new File(fNameMap.getAbsolutePath());
            }
            
            osNameMap.close();
            baosNameMap.writeTo(osNameMap = new FileOutputStream(fNameMap));
            osNameMap.close();
            
            if (compress) compressTotal += new File(fNameMap.getAbsolutePath()).length();

            log("Optimization of all files: " + (int)beforeTotal + " to " + (int)afterTotal + " bytes, " + ((10000 - Math.round((afterTotal / beforeTotal) * 10000)) / 100) + "% reduction");
            
            if (compress) {
                log("Compression of all files: " + (int)afterTotal + " to " + (int)compressTotal + " bytes, " + ((10000 - Math.round((compressTotal / afterTotal) * 10000)) / 100) + "% reduction");
                log("Total reduction of all files: " + (int)beforeTotal + " to " + (int)compressTotal + " bytes, " + ((10000 - Math.round((compressTotal / beforeTotal) * 10000)) / 100) + "% reduction");
            }
        
        } catch (Exception e) {
            throw new MojoExecutionException("Exception executing mojo", e);
        }
        
    }
    
    private void log(CharSequence message) {
        getLog().info(message);
    }
    
    private String getDestPath(File f) throws IOException {
        String fPath = f.getCanonicalPath();
        String srcPath = srcdir.getCanonicalPath();
        fPath = fPath.substring(fPath.indexOf(srcPath) + srcPath.length());
        if (fPath.substring(0, 1).matches("[\\\\/:]")) fPath = fPath.substring(1);
        return fPath;
    }
}
