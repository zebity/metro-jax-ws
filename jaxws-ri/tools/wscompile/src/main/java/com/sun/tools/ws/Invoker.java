/*
 * Copyright (c) 1997, 2022 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package com.sun.tools.ws;

import com.sun.istack.tools.MaskingClassLoader;
import com.sun.istack.tools.ParallelWorldClassLoader;
import com.sun.tools.ws.resources.WscompileMessages;
import com.sun.tools.ws.wscompile.Options;
import org.glassfish.jaxb.core.util.Which;

import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceFeature;
import javax.xml.namespace.QName;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Invokes JAX-WS tools in a special class loader that can pick up annotation processing classes,
 * even if it's not available in the tool launcher classpath.
 *
 * @author Kohsuke Kawaguchi
 */
public final class Invoker {

    /**
     * The list of package prefixes we want the
     * {@link MaskingClassLoader} to prevent the parent
     * class loader from loading
     */
    static final String[] maskedPackages = new String[]{
            "com.sun.istack.tools.",
            "com.sun.tools.jxc.",
            "com.sun.tools.xjc.",
            "com.sun.tools.ws.",
            "com.sun.codemodel.",
            "com.sun.relaxng.",
            "com.sun.xml.xsom.",
            "com.sun.xml.bind.",
            "org.glassfish.jaxb.",
            "com.ctc.wstx.", //wsimport, wsgen ant task
            "org.codehaus.stax2.", //wsimport, wsgen ant task
            "com.sun.xml.messaging.saaj.", //wsgen ant task
            "com.sun.xml.ws.",
            "com.oracle.webservices.api." //wsgen
    };

    /**
     * Escape hatch to work around IBM JDK problem.
     * See http://www-128.ibm.com/developerworks/forums/dw_thread.jsp?nav=false&amp;forum=367&amp;thread=164718&amp;cat=10
     */
    public static final boolean noSystemProxies;

    static {
        boolean noSysProxiesProperty = false;
        try {
            noSysProxiesProperty = Boolean.getBoolean(Invoker.class.getName()+".noSystemProxies");
        } catch(SecurityException e) {
            // ignore
        } finally {
            noSystemProxies = noSysProxiesProperty;
        }
    }

    private Invoker() {}

    static int invoke(String mainClass, String[] args) throws Throwable {
        // use the platform default proxy if available.
        // see sun.net.spi.DefaultProxySelector for details.
        if(!noSystemProxies) {
            try {
                System.setProperty("java.net.useSystemProxies","true");
            } catch (SecurityException e) {
                // failing to set this property isn't fatal
            }
        }

        ClassLoader oldcc = Thread.currentThread().getContextClassLoader();
        try {
            ClassLoader cl = Invoker.class.getClassLoader();
            //XXX - kept here for the future to resurect ability to run with older apis
//            if(Arrays.asList(args).contains("-Xendorsed"))
//                cl = createClassLoader(cl); // perform JDK6 workaround hack
//            else {
                int targetArgIndex = Arrays.asList(args).indexOf("-target"); 
                Options.Target targetVersion;
                if (targetArgIndex != -1) {
                    targetVersion = Options.Target.parse(args[targetArgIndex+1]);
                } else {
                    targetVersion = Options.Target.getDefault();
                }
                Options.Target loadedVersion = Options.Target.getLoadedAPIVersion();

                //Check if the target version is supported by the loaded API version
                if (!loadedVersion.isLaterThan(targetVersion)) {
                    if (Service.class.getClassLoader() == null)
                        System.err.println(WscompileMessages.INVOKER_NEED_ENDORSED(loadedVersion.getVersion(), targetVersion.getVersion()));
                    else {
                        System.err.println(WscompileMessages.WRAPPER_TASK_LOADING_INCORRECT_API(loadedVersion.getVersion(), Which.which(Service.class), targetVersion.getVersion()));
                    }
                    return -1;
                }

//            }
            //if loaded by bootstrap, cl can be null, let's use the loader
            //we have in that case
            if (cl == null) {
                cl = oldcc;
            }

            Thread.currentThread().setContextClassLoader(cl);

            Class compileTool = cl.loadClass(mainClass);
            Constructor ctor = compileTool.getConstructor(OutputStream.class);
            Object tool = ctor.newInstance(System.out);
            Method runMethod = compileTool.getMethod("run",String[].class);
            boolean r = (Boolean)runMethod.invoke(tool,new Object[]{args});
            return r ? 0 : 1;
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            Thread.currentThread().setContextClassLoader(oldcc);
        }
    }

    /**
     * Returns true if the RI appears to be loading the JAX-WS 2.1 API.
     */
    public static boolean checkIfLoading21API() {
        try {
            Service.class.getMethod("getPort",Class.class, WebServiceFeature[].class);
            // yup. things look good.
            return true;
        } catch (NoSuchMethodException | LinkageError e) {
        }
        // nope
        return false;
    }

    /**
    * Returns true if the RI appears to be loading the JAX-WS 2.2 API.
    */
   public static boolean checkIfLoading22API() {
       try {
           Service.class.getMethod("create",java.net.URL.class, QName.class, WebServiceFeature[].class);
           // yup. things look good.
           return true;
       } catch (NoSuchMethodException | LinkageError e) {
       }
       // nope
       return false;
   }


    /**
     * Creates a class loader that can load JAXB/WS 2.2 API,
     * and then return a class loader that can RI classes, which can see all those APIs.
     */
    public static ClassLoader createClassLoader(ClassLoader cl) throws ClassNotFoundException, IOException {

        URL[] urls = findIstack22APIs(cl);
        if(urls.length==0)
            return cl;  // we seem to be able to load everything already. no need for the hack

        List<String> mask = new ArrayList<>(Arrays.asList(maskedPackages));
        if(urls.length>1) {
            // we need to load 2.1 API from side. so add them to the mask
            mask.add("jakarta.xml.bind.");
            mask.add("jakarta.xml.ws.");
        }

        // first create a protected area so that we load JAXB/WS 2.1 API
        // and everything that depends on them inside
        cl = new MaskingClassLoader(cl,mask);

        // then this class loader loads the API
        cl = new URLClassLoader(urls, cl);

        // finally load the rest of the RI. The actual class files are loaded from ancestors
        cl = new ParallelWorldClassLoader(cl,"");

        return cl;
    }

    /**
     * Creates a class loader for loading JAXB/WS 2.2 jar
     */
    private static URL[] findIstack22APIs(ClassLoader cl) throws ClassNotFoundException, IOException {
        List<URL> urls = new ArrayList<>();

        if(Service.class.getClassLoader()==null) {
            // JAX-WS API is loaded from bootstrap class loader
            URL res = cl.getResource("jakarta/xml/ws/EndpointContext.class");
            if(res==null)
                throw new ClassNotFoundException("There's no JAX-WS 2.2 API in the classpath");
            urls.add(ParallelWorldClassLoader.toJarUrl(res));
            res = cl.getResource("jakarta/xml/bind/JAXBPermission.class");
            if(res==null)
                throw new ClassNotFoundException("There's no JAXB 2.2 API in the classpath");
            urls.add(ParallelWorldClassLoader.toJarUrl(res));
        }

        return urls.toArray(new URL[0]);
    }

}
