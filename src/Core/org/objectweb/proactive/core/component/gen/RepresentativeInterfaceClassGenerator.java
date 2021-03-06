/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2012 INRIA/University of
 *                 Nice-Sophia Antipolis/ActiveEon
 * Contact: proactive@ow2.org or contact@activeeon.com
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; version 3 of
 * the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
 * USA
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 *
 *  Initial developer(s):               The ProActive Team
 *                        http://proactive.inria.fr/team_members.htm
 *  Contributor(s):
 *
 * ################################################################
 * $$PROACTIVE_INITIAL_DEV$$
 */
package org.objectweb.proactive.core.component.gen;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.LoaderClassPath;
import javassist.Modifier;
import javassist.NotFoundException;

import org.etsi.uri.gcm.api.type.GCMTypeFactory;
import org.objectweb.fractal.api.Component;
import org.objectweb.fractal.api.factory.InstantiationException;
import org.objectweb.proactive.api.PAGroup;
import org.objectweb.proactive.core.component.ItfStubObject;
import org.objectweb.proactive.core.component.PAInterface;
import org.objectweb.proactive.core.component.PAInterfaceImpl;
import org.objectweb.proactive.core.component.exceptions.InterfaceGenerationFailedException;
import org.objectweb.proactive.core.component.type.PAGCMInterfaceType;
import org.objectweb.proactive.core.component.type.PAGCMTypeFactoryImpl;
import org.objectweb.proactive.core.component.type.annotations.multicast.Reduce;
import org.objectweb.proactive.core.mop.JavassistByteCodeStubBuilder;
import org.objectweb.proactive.core.mop.StubObject;
import org.objectweb.proactive.core.util.ClassDataCache;


/**
 * This class generates representative interfaces objects, which are created on
 * the client side along with the component representative object (@see
 * org.objectweb.proactive.core.component.representative.PAComponentRepresentativeImpl).
 *
 * @author The ProActive Team
 */
public class RepresentativeInterfaceClassGenerator extends AbstractInterfaceClassGenerator {
    private static RepresentativeInterfaceClassGenerator instance;

    // this boolean for deciding of a possible indirection for the functionnal calls
    protected boolean isPrimitive = false;

    private RepresentativeInterfaceClassGenerator() {
    }

    public synchronized static RepresentativeInterfaceClassGenerator instance() {
        if (instance == null) {
            instance = new RepresentativeInterfaceClassGenerator();
        }

        return instance;
    }

    @Override
    public PAInterface generateInterface(final String interfaceName, Component owner,
            PAGCMInterfaceType interfaceType, boolean isInternal, boolean isFunctionalInterface)
            throws InterfaceGenerationFailedException {
        try {
            Class<?> generated_class = generateInterfaceClass(interfaceType, isFunctionalInterface);

            PAInterfaceImpl reference = (PAInterfaceImpl) generated_class.newInstance();
            reference.setFcItfName(interfaceName);
            reference.setFcItfOwner(owner);
            reference.setFcType(interfaceType);
            reference.setFcIsInternal(isInternal);

            return reference;
        } catch (Exception e) {
            throw new InterfaceGenerationFailedException(
                "Cannot generate representative on interface [" + interfaceName + "] with signature [" +
                    interfaceType.getFcItfSignature() + "] with javassist", e);
        }
    }

    public synchronized Class<?> generateInterfaceClass(PAGCMInterfaceType itfType,
            boolean isFunctionalInterface) {
        if (GCMTypeFactory.GATHERCAST_CARDINALITY.equals(itfType.getGCMCardinality())) {
            // modify signature in type
            try {
                Class<?> gatherProxyItf = GatherInterfaceGenerator.generateInterface(itfType);
                itfType = (PAGCMInterfaceType) PAGCMTypeFactoryImpl.instance().createGCMItfType(
                        itfType.getFcItfName(), gatherProxyItf.getName(), itfType.isFcClientItf(),
                        itfType.isFcOptionalItf(), itfType.getGCMCardinality());
            } catch (InstantiationException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        String representativeClassName = org.objectweb.proactive.core.component.gen.Utils
                .getMetaObjectComponentRepresentativeClassName(itfType.getFcItfName(), itfType
                        .getFcItfSignature());
        Class<?> generated_class = null;

        // check whether class has already been generated
        try {
            generated_class = loadClass(representativeClassName);
        } catch (ClassNotFoundException cnfe) {
            byte[] bytecode = generateInterfaceByteCode(representativeClassName, itfType);

            try {
                // convert the bytes into a Class<?>
                generated_class = Utils.defineClass(representativeClassName, bytecode);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        return generated_class;
    }

    public synchronized static byte[] generateInterfaceByteCode(String representativeClassName,
            PAGCMInterfaceType itfType) {
        try {
            if (itfType == null) {
                // infer a mock type from signature of representative
                String name = Utils.getInterfaceNameFromRepresentativeClassName(representativeClassName);
                String signature = Utils
                        .getInterfaceSignatureFromRepresentativeClassName(representativeClassName);
                itfType = (PAGCMInterfaceType) PAGCMTypeFactoryImpl.instance().createFcItfType(name,
                        signature, false, false, false);
            }
            String interfaceName = Utils.getMetaObjectComponentRepresentativeClassName(
                    itfType.getFcItfName(), itfType.getFcItfSignature());
            CtMethod[] reifiedMethods;
            CtClass generatedCtClass = null;
            try {
                generatedCtClass = pool.makeClass(representativeClassName);
            } catch (RuntimeException e) {
                // the CtClass is frozen in Javassist, 
                // so the class has been generated while we were waiting because of the synchronization
                // we just have to retrieve and return the cached bytecode 
                return ClassDataCache.instance().getClassData(representativeClassName);
            }

            List<CtClass> interfacesToImplement = new ArrayList<CtClass>();

            // add interface to reify
            CtClass functional_itf = null;
            try {
                functional_itf = pool.get(itfType.getFcItfSignature());
            } catch (NotFoundException nfe) {
                // may happen in environments with multiple classloaders: itfType.getFcItfSignature() is not
                // available in the initial classpath of javassist's class pool
                // ==> try to append classpath of the class corresponding to itfType.getFcItfSignature()
                pool.appendClassPath(new LoaderClassPath(Class.forName(itfType.getFcItfSignature())
                        .getClassLoader()));
                functional_itf = pool.get(itfType.getFcItfSignature());
            }

            generatedCtClass.addInterface(functional_itf);

            interfacesToImplement.add(functional_itf);

            // add Serializable interface
            interfacesToImplement.add(pool.get(Serializable.class.getName()));
            generatedCtClass.addInterface(pool.get(Serializable.class.getName()));

            // add StubObject, so we can set the proxy
            generatedCtClass.addInterface(pool.get(StubObject.class.getName()));

            // add ItfStubObject, so we can set the sender itf
            generatedCtClass.addInterface(pool.get(ItfStubObject.class.getName()));
            Utils.createItfStubObjectMethods(generatedCtClass);

            // interfacesToImplement.add(pool.get(StubObject.class.getName()));
            List<CtClass> interfacesToImplementAndSuperInterfaces = new ArrayList<CtClass>(
                interfacesToImplement);
            addSuperInterfaces(interfacesToImplementAndSuperInterfaces);
            generatedCtClass.setSuperclass(pool.get(PAInterfaceImpl.class.getName()));
            JavassistByteCodeStubBuilder.createStubObjectMethods(generatedCtClass);
            CtField interfaceNameField = new CtField(ClassPool.getDefault().get(String.class.getName()),
                "interfaceName", generatedCtClass);
            interfaceNameField.setModifiers(Modifier.STATIC);
            generatedCtClass.addField(interfaceNameField, "\"" + interfaceName + "\"");

            CtField methodsField = new CtField(pool.get("java.lang.reflect.Method[]"), "overridenMethods",
                generatedCtClass);
            methodsField.setModifiers(Modifier.STATIC);

            generatedCtClass.addField(methodsField);

            // field for remembering generic parameters
            CtField genericTypesMappingField = new CtField(pool.get("java.util.Map"), "genericTypesMapping",
                generatedCtClass);

            genericTypesMappingField.setModifiers(Modifier.STATIC);
            generatedCtClass.addField(genericTypesMappingField);

            String bodyForImplGetterAndSetter = "{throw new org.objectweb.proactive.core.ProActiveRuntimeException(\" representative interfaces do not implement getFcItfImpl or setFcItfImpl methods\");}";

            CtMethod implGetter = CtNewMethod.make("public Object getFcItfImpl() " +
                bodyForImplGetterAndSetter, generatedCtClass);
            generatedCtClass.addMethod(implGetter);
            CtMethod implSetter = CtNewMethod.make("public void setFcItfImpl(Object o) " +
                bodyForImplGetterAndSetter, generatedCtClass);
            generatedCtClass.addMethod(implSetter);

            // list all methods to implement
            Map<String, CtMethod> methodsToImplement = new HashMap<String, CtMethod>();
            List<String> classesIndexer = new Vector<String>();

            CtClass[] params;
            CtClass itf;

            // now get the methods from implemented interfaces
            Iterator<CtClass> it = interfacesToImplementAndSuperInterfaces.iterator();

            while (it.hasNext()) {
                itf = it.next();

                if (!classesIndexer.contains(itf.getName())) {
                    classesIndexer.add(itf.getName());
                }

                CtMethod[] declaredMethods = itf.getDeclaredMethods();

                for (int i = 0; i < declaredMethods.length; i++) {
                    CtMethod currentMethod = declaredMethods[i];

                    // Build a key with the simple name of the method
                    // and the names of its parameters in the right order
                    String key = "";
                    key = key + currentMethod.getName();
                    params = currentMethod.getParameterTypes();

                    for (int k = 0; k < params.length; k++) {
                        key = key + params[k].getName();
                    }

                    // this gives the actual declaring Class<?> of this method
                    methodsToImplement.put(key, currentMethod);
                }
            }

            reifiedMethods = methodsToImplement.values().toArray(new CtMethod[methodsToImplement.size()]);

            // Determines which reifiedMethods are valid for reification
            // It is the responsibility of method checkMethod in class Utils
            // to decide if a method is valid for reification or not
            Vector<CtMethod> v = new Vector<CtMethod>();
            int initialNumberOfMethods = reifiedMethods.length;

            for (int i = 0; i < initialNumberOfMethods; i++) {
                if (JavassistByteCodeStubBuilder.checkMethod(reifiedMethods[i])) {
                    v.addElement(reifiedMethods[i]);
                }
            }

            CtMethod[] validMethods = new CtMethod[v.size()];
            v.copyInto(validMethods);

            reifiedMethods = validMethods;

            JavassistByteCodeStubBuilder.createStaticInitializer(generatedCtClass, reifiedMethods,
                    classesIndexer, itfType.getFcItfSignature(), null);

            createReifiedMethods(generatedCtClass, reifiedMethods, itfType);
            //                                    generatedCtClass.stopPruning(true);
            //                                    generatedCtClass.writeFile("generated/");
            //                                    System.out.println("[JAVASSIST] generated class : " +
            //                                        representativeClassName);
            byte[] bytecode = generatedCtClass.toBytecode();
            ClassDataCache.instance().addClassData(representativeClassName, bytecode);

            if (logger.isDebugEnabled()) {
                logger.debug("added " + representativeClassName + " to cache");
            }

            return bytecode;
        } catch (Exception e) {
            e.printStackTrace();

            logger.error("Cannot generate class : " + representativeClassName);
            return null;
        }
    }

    protected static void createReifiedMethods(CtClass generatedClass, CtMethod[] reifiedMethods,
            PAGCMInterfaceType itfType) throws NotFoundException, CannotCompileException,
            ClassNotFoundException, SecurityException, NoSuchMethodException {

        Class<?> itfClass = Class.forName(itfType.getFcItfSignature());

        for (int i = 0; i < reifiedMethods.length; i++) {
            CtClass[] paramTypes = reifiedMethods[i].getParameterTypes();
            String body = ("{\nObject[] parameters = new Object[" + paramTypes.length + "];\n");

            for (int j = 0; j < paramTypes.length; j++) {
                if (paramTypes[j].isPrimitive()) {
                    body += ("  parameters[" + j + "]=" +
                        JavassistByteCodeStubBuilder.wrapPrimitiveParameter(paramTypes[j], "$" + (j + 1)) + ";\n");
                } else {
                    body += ("  parameters[" + j + "]=$" + (j + 1) + ";\n");
                }
            }

            CtClass returnType = reifiedMethods[i].getReturnType();
            String postWrap = null;
            String preWrap = "";
            String reduction = "";

            if (returnType != CtClass.voidType) {
                if ((itfType != null) && itfType.isGCMMulticastItf()) {
                    body += "Object result = null;\n";

                    // look for reduction closure
                    CtClass[] parametersCtTypes = reifiedMethods[i].getParameterTypes();
                    Class<?>[] parametersTypes = new Class[parametersCtTypes.length];
                    for (int j = 0; j < parametersCtTypes.length; j++) {
                        parametersTypes[j] = Class.forName(parametersCtTypes[j].getName());
                    }
                    Method itfMethod = itfClass.getMethod(reifiedMethods[i].getName(), parametersTypes);
                    Reduce reduceAnnotation = itfMethod.getAnnotation(Reduce.class);

                    if (reduceAnnotation == null) {
                        preWrap += PAGroup.class.getName() + ".getGroup(";
                        postWrap = ")";
                    }
                } else if (!returnType.isPrimitive()) {
                    body += "Object result = null;\n";
                    preWrap = "(" + returnType.getName() + ")";
                } else {
                    // boolean, byte, char, short, int, long, float, double
                    if (returnType.equals(CtClass.booleanType)) {
                        body += "boolean result;\n";
                        preWrap = "((Boolean)";
                        postWrap = ").booleanValue()";
                    }

                    if (returnType.equals(CtClass.byteType)) {
                        body += "byte result;\n";
                        preWrap = "((Byte)";
                        postWrap = ").byteValue()";
                    }

                    if (returnType.equals(CtClass.charType)) {
                        body += "char result;\n";
                        preWrap = "((Character)";
                        postWrap = ").charValue()";
                    }

                    if (returnType.equals(CtClass.shortType)) {
                        body += "short result;\n";
                        preWrap = "((Short)";
                        postWrap = ").shortValue()";
                    }

                    if (returnType.equals(CtClass.intType)) {
                        body += "int result;\n";
                        preWrap = "((Integer)";
                        postWrap = ").intValue()";
                    }

                    if (returnType.equals(CtClass.longType)) {
                        body += "long result;\n";
                        preWrap = "((Long)";
                        postWrap = ").longValue()";
                    }

                    if (returnType.equals(CtClass.floatType)) {
                        body += "float result;\n";
                        preWrap = "((Float)";
                        postWrap = ").floatValue()";
                    }

                    if (returnType.equals(CtClass.doubleType)) {
                        body += "double result;\n";
                        preWrap = "((Double)";
                        postWrap = ").doubleValue()";
                    }
                }

                body += "result = ";

                body += preWrap;
            }

            body += (" myProxy.reify(org.objectweb.proactive.core.mop.MethodCall.getComponentMethodCall(" +
                "(java.lang.reflect.Method)overridenMethods[" + i + "]" + ", parameters, null, getFcItfName(), senderItfID))  ");

            if (postWrap != null) {
                body += postWrap;
            }
            body += ";\n";

            if (returnType != CtClass.voidType) {
                if (!returnType.isPrimitive()) {
                    body += reduction;
                    // need a cast from List to actual return type
                    body += "return (" + returnType.getName() + ")result;\n";
                } else {
                    body += "return result;\n";
                }
            }

            body += "\n}";
            //			 System.out.println("method : " + reifiedMethods[i].getName() +
            //			 " : \n" + body);
            CtMethod methodToGenerate = CtNewMethod.make(reifiedMethods[i].getReturnType(), reifiedMethods[i]
                    .getName(), reifiedMethods[i].getParameterTypes(), reifiedMethods[i].getExceptionTypes(),
                    body, generatedClass);
            generatedClass.addMethod(methodToGenerate);
        }
    }
}
