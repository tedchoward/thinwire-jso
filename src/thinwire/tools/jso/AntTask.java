/*
                        ThinWire(R) JavaScript Optimizer
                 Copyright (C) 2006-2007 Custom Credit Systems

  This library is free software; you can redistribute it and/or modify it under
  the terms of the GNU Lesser General Public License as published by the Free
  Software Foundation; either version 2.1 of the License, or (at your option) any
  later version.

  This library is distributed in the hope that it will be useful, but WITHOUT ANY
  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License along
  with this library; if not, write to the Free Software Foundation, Inc., 59
  Temple Place, Suite 330, Boston, MA 02111-1307 USA

  Users interested in finding out more about the ThinWire framework should visit
  the ThinWire framework website at http://www.thinwire.com. For those interested
  in discussing the details of how this tool was built, you can contact the 
  developer via email at "Joshua Gertzen" <josh at truecode dot org>.
*/
package thinwire.tools.jso;

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

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.selectors.FilenameSelector;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;

//NOTE: To test, run the testjso task in the build.xml with Ant.
public class AntTask extends MatchingTask {        
    private File srcdir;
    private File destdir;
    private String namemap;
    private boolean verify = true;
    private boolean compress;
    
    public void setDestdir(File destdir) {
        this.destdir = destdir;
    }
    
    public void setSrcdir(File srcdir) {
        this.srcdir = srcdir;
    }
    
    public void setNamemap(String namemap) {
        this.namemap = namemap;
    }
    
    public void setVerify(boolean verify) {
        this.verify = verify;
    }
    
    public void setCompress(boolean compress) {
        this.compress = compress;
    }
    
    public void execute() throws BuildException {
        if (srcdir == null) throw new BuildException("srcdir must be specified");
        if (destdir == null) throw new BuildException("destdir must be specified");
        if (namemap == null) throw new BuildException("namemap must be specified");
        float beforeTotal = 0;
        float afterTotal = 0;
        float compressTotal = 0;
        
        try {
            DirectoryScanner ds = getDirectoryScanner(srcdir);
            List<File> lst = new ArrayList<File>();
            
            for (String s : ds.getIncludedFiles()) {
                File f = new File(srcdir, s);
                lst.add(f);
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
            throw new BuildException(e);                
        }
    }
    
    private String getDestPath(File f) throws IOException {
        String fPath = f.getCanonicalPath();
        String srcPath = srcdir.getCanonicalPath();
        fPath = fPath.substring(fPath.indexOf(srcPath) + srcPath.length());
        if (fPath.substring(0, 1).matches("[\\\\/:]")) fPath = fPath.substring(1);
        return fPath;
    }
}