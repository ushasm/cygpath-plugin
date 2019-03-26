/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Seiji Sogabe, Stephen Connolly
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.cygpath;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.remoting.Callable;
import hudson.util.IOException2;
import hudson.util.jna.JnaException;
import hudson.util.jna.RegistryKey;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * If we are on Windows, convert the path of the executable via Cygwin.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class CygpathLauncherDecorator extends LauncherDecorator {
    public Launcher decorate(final Launcher base, Node node) {
        if(base.isUnix())   return base;    // no decoration on Unix

        return new Launcher(base) {
            @Override
            public boolean isUnix() {
                return base.isUnix();
            }

            public Proc launch(ProcStarter starter) throws IOException {
                starter = starter.copy();
                List<String> cmds = starter.cmds();
                starter.cmds(cygpath(cmds.toArray(new String[cmds.size()])));
                return base.launch(starter);
            }

            @Override
            public Channel launchChannel(String[] cmd, OutputStream out, FilePath workDir, Map<String, String> envVars) throws IOException, InterruptedException {
                return base.launchChannel(cygpath(cmd),out,workDir,envVars);
            }

            @Override
            public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
                base.kill(modelEnvVars);
            }

            private String[] cygpath(String[] cmds) throws IOException {
                try {
                    String exe = cmds[0];
                    if (exe.indexOf('/')<0 && exe.indexOf('\\')<0)
                        return cmds;    // if the executable is a single token, it'll be found in PATH. "cygpath -w" would append the current directory in front, which won't work.

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if(base.launch().cmds(getCygpathExe(),"-w",exe).stdout(out).join()==0) {
                        // replace by the converted path
                        String cmd = out.toString().trim();
                        if(cmd.length()>0)
                            // Maybe a bug in cygwin 1.7, or maybe a race condition that I'm not aware of yet,
                            // but until I get around to investigate that, I'm putting this defensive check
                            cmds[0] = cmd;
                    }
                } catch (InterruptedException e) {
                    // handle the interrupt later
                    Thread.currentThread().interrupt();
                }
                return cmds;
            }

            private String getCygpathExe() throws IOException, InterruptedException {
                VirtualChannel ch = base.getChannel();
                if (ch==null)   return "cygpath";   // fall back
                return ch.call(new GetCygpathTask());
            }
        };
    }

    /**
     * Where is Cygwin installed?
     */
    private static class GetCygpathTask implements Callable<String,IOException> {
        private File getCygwinRoot() throws IOException {
            JnaException err=null;
            for (String prefix : new String[]{"SOFTWARE\\Wow6432Node\\","SOFTWARE\\"}) {
                try {// Cygwin 1.7
                    Process process = Runtime.getRuntime().exec("REG QUERY HKEY_LOCAL_MACHINE\\" + prefix + "Cygwin\\setup");
                    InputStream is = process.getInputStream();
                    StringBuilder sw = new StringBuilder();
                    try {
                        int c;
                        while ((c = is.read()) != -1)
                            sw.append((char)c);
                    }
                    catch (IOException e) {
                          LOGGER.log(Level.SEVERE, "Exception raised" + e);
                          break;
                    }

                    String output = sw.toString();
                    if(output.isEmpty()) throw new JnaException(1); //check next value in for loop

                    String[] bits = output.split(" ");
                    String key = bits[bits.length-1];
                    key = key.replaceAll("\r", "").replaceAll("\n", "").replaceAll(" ", "");
                    LOGGER.log(Level.INFO, "Cygwin path for " + prefix + " is " + key);

                    try {
                        return new File(key);
                    }
                    catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to return Cygwin path" + e);
                       // fall through
                    }
                } catch (JnaException e) {
                    err = e; // fall through
                }
            }

            throw new IOException2("Failed to locate Cygwin installation. Is Cygwin installed?",err);
        }

        public String call() throws IOException {
            return new File(getCygwinRoot(),"bin\\cygpath").getPath();
        }
    }
    private static final Logger LOGGER = Logger.getLogger(CygpathLauncherDecorator.class.getName());
}
