/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.code;

import java.util.Collection;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.java.AbstractNewObjectNode;
import org.graalvm.compiler.options.Option;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.meta.HostedMethod;

public final class UninterruptibleAnnotationChecker {

    /*
     * Command line options so errors are not fatal to the build.
     */
    public static class Options {
        @Option(help = "Print warnings for @Uninterruptible annotations.")//
        public static final HostedOptionKey<Boolean> PrintUninterruptibleWarnings = new HostedOptionKey<>(true);

        @Option(help = "Warnings for @Uninterruptible annotations are fatal.")//
        public static final HostedOptionKey<Boolean> UninterruptibleWarningsAreFatal = new HostedOptionKey<>(true);

        @Option(help = "Print (to stderr) a DOT graph of the @Uninterruptible annotations.")//
        public static final HostedOptionKey<Boolean> PrintUninterruptibleCalleeDOTGraph = new HostedOptionKey<>(false);
    }

    private final Collection<HostedMethod> methodCollection;

    /** Private constructor: Use {@link UninterruptibleAnnotationChecker#check}. */
    private UninterruptibleAnnotationChecker(Collection<HostedMethod> methodCollection) {
        this.methodCollection = methodCollection;
    }

    /** Check that {@linkplain Uninterruptible} has been used consistently. */
    public static void check(DebugContext debug, Collection<HostedMethod> methodCollection) {
        final UninterruptibleAnnotationChecker checker = new UninterruptibleAnnotationChecker(methodCollection);
        checker.checkUninterruptibleOverrides(debug);
        checker.checkUninterruptibleCallees(debug);
        checker.checkUninterruptibleCallers(debug);
        checker.checkUninterruptibleAllocations(debug);
    }

    /**
     * Check that each method annotated with {@linkplain Uninterruptible} is overridden with
     * implementations that are also annotated with {@linkplain Uninterruptible}, with the same
     * values.
     *
     * The reverse need not be true: An overriding method can be annotated with
     * {@linkplain Uninterruptible} even though the overridden method is not annotated with
     * {@linkplain Uninterruptible}.
     *
     * TODO: The check for the same values might be too strict.
     */
    @SuppressWarnings("try")
    private void checkUninterruptibleOverrides(DebugContext debug) {
        for (HostedMethod method : methodCollection) {
            try (DebugContext.Scope s = debug.scope("CheckUninterruptibleOverrides", method.compilationInfo.graph, method, this)) {
                Uninterruptible methodAnnotation = method.getAnnotation(Uninterruptible.class);
                if (methodAnnotation != null) {
                    for (HostedMethod impl : method.getImplementations()) {
                        Uninterruptible implAnnotation = impl.getAnnotation(Uninterruptible.class);
                        if (implAnnotation != null) {
                            if (methodAnnotation.callerMustBe() != implAnnotation.callerMustBe()) {
                                postUninterruptibleWarning("callerMustBe: " + method.format("%h.%n(%p)") + " != " + impl.format("%h.%n(%p)"));
                            }
                            if (methodAnnotation.calleeMustBe() != implAnnotation.calleeMustBe()) {
                                postUninterruptibleWarning("calleeMustBe: " + method.format("%h.%n(%p)") + " != " + impl.format("%h.%n(%p)"));
                            }
                        } else {
                            postUninterruptibleWarning("method " + method.format("%h.%n(%p)") + " is annotated but " + impl.format("%h.%n(%p)" + " is not"));
                        }
                    }
                }
            } catch (Throwable t) {
                throw debug.handle(t);
            }
        }
    }

    /**
     * Check that each method annotated with {@link Uninterruptible} calls only methods that are
     * also annotated with {@link Uninterruptible}, or methods annotated with {@link CFunction} that
     * specify "Transition = NO_TRANSITION".
     *
     * A caller can be annotated with "calleeMustBe = false" to allow calls to methods that are not
     * annotated with {@link Uninterruptible}, to allow the few cases where that should be allowed.
     */
    @SuppressWarnings("try")
    private void checkUninterruptibleCallees(DebugContext debug) {
        if (Options.PrintUninterruptibleCalleeDOTGraph.getValue()) {
            System.out.println("/* DOT */ digraph uninterruptible {");
        }
        for (HostedMethod caller : methodCollection) {
            try (DebugContext.Scope s = debug.scope("CheckUninterruptibleCallees", caller.compilationInfo.graph, caller, this)) {
                Uninterruptible callerAnnotation = caller.getAnnotation(Uninterruptible.class);
                StructuredGraph graph = caller.compilationInfo.getGraph();
                if (callerAnnotation != null) {
                    if (callerAnnotation.calleeMustBe()) {
                        if (graph != null) {
                            for (Invoke invoke : graph.getInvokes()) {
                                HostedMethod callee = (HostedMethod) invoke.callTarget().targetMethod();
                                if (Options.PrintUninterruptibleCalleeDOTGraph.getValue()) {
                                    printDotGraphEdge(caller, callee);
                                }
                                if (!isNotInterruptible(callee)) {
                                    postUninterruptibleWarning("Unannotated callee: " + callee.format("%h.%n(%p)") + " called by annotated caller " + caller.format("%h.%n(%p)"));
                                }
                            }
                        }
                    } else {
                        // Print DOT graph edge even if callee need not be annotated.
                        if (graph != null) {
                            for (Invoke invoke : graph.getInvokes()) {
                                HostedMethod callee = (HostedMethod) invoke.callTarget().targetMethod();
                                if (Options.PrintUninterruptibleCalleeDOTGraph.getValue()) {
                                    printDotGraphEdge(caller, callee);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                throw debug.handle(t);
            }
        }
        if (Options.PrintUninterruptibleCalleeDOTGraph.getValue()) {
            System.out.println("/* DOT */ }");
        }
    }

    /**
     * Check that each method that calls a method annotated with {@linkplain Uninterruptible} that
     * has "callerMustBeUninterrutible = true" is also annotated with {@linkplain Uninterruptible}.
     */
    @SuppressWarnings("try")
    private void checkUninterruptibleCallers(DebugContext debug) {
        for (HostedMethod caller : methodCollection) {
            try (DebugContext.Scope s = debug.scope("CheckUninterruptibleCallers", caller.compilationInfo.graph, caller, this)) {
                Uninterruptible callerAnnotation = caller.getAnnotation(Uninterruptible.class);
                StructuredGraph graph = caller.compilationInfo.getGraph();
                if (callerAnnotation == null && graph != null) {
                    for (Invoke invoke : graph.getInvokes()) {
                        HostedMethod callee = (HostedMethod) invoke.callTarget().targetMethod();
                        if (isCallerMustBe(callee)) {
                            postUninterruptibleWarning("Unannotated caller: " + caller.format("%h.%n(%p)") + " calls annotated callee " + callee.format("%h.%n(%p)"));
                        }
                    }
                }
            } catch (Throwable t) {
                throw debug.handle(t);
            }
        }
    }

    /**
     * Check that each method that is annotated with {@linkplain Uninterruptible} contains no
     * allocations.
     */
    @SuppressWarnings("try")
    private void checkUninterruptibleAllocations(DebugContext debug) {
        for (HostedMethod method : methodCollection) {
            try (DebugContext.Scope s = debug.scope("CheckUninterruptibleAllocations", method.compilationInfo.graph, method, this)) {
                Uninterruptible methodAnnotation = method.getAnnotation(Uninterruptible.class);
                StructuredGraph graph = method.compilationInfo.getGraph();
                if (methodAnnotation != null && graph != null) {
                    for (Node node : graph.getNodes()) {
                        if (node instanceof AbstractNewObjectNode) {
                            postUninterruptibleWarning("Annotated method: " + method.format("%h.%n(%p)") + " allocates.");
                        }
                    }
                }
            } catch (Throwable t) {
                throw debug.handle(t);
            }
        }
    }

    private static void postUninterruptibleWarning(String warning) throws WarningException {
        final String message = "@Uninterruptible warning: " + warning;
        if (Options.PrintUninterruptibleWarnings.getValue()) {
            System.err.println(message);
        }
        if (Options.UninterruptibleWarningsAreFatal.getValue()) {
            throw new WarningException(message);
        }
    }

    private static boolean isNotInterruptible(HostedMethod method) {
        return (isUninterruptible(method) || isNoTransitionCFunction(method));
    }

    private static boolean isUninterruptible(HostedMethod method) {
        return (method.getAnnotation(Uninterruptible.class) != null);
    }

    private static boolean isCallerMustBe(HostedMethod method) {
        final Uninterruptible uninterruptibleAnnotation = method.getAnnotation(Uninterruptible.class);
        return ((uninterruptibleAnnotation != null) && uninterruptibleAnnotation.callerMustBe());
    }

    private static boolean isCalleeMustBe(HostedMethod method) {
        final Uninterruptible uninterruptibleAnnotation = method.getAnnotation(Uninterruptible.class);
        return ((uninterruptibleAnnotation != null) && uninterruptibleAnnotation.calleeMustBe());
    }

    private static boolean isNoTransitionCFunction(HostedMethod method) {
        final CFunction cfunctionAnnotation = method.getAnnotation(CFunction.class);
        return ((cfunctionAnnotation != null) && (cfunctionAnnotation.transition() == Transition.NO_TRANSITION));
    }

    private static void printDotGraphEdge(HostedMethod caller, HostedMethod callee) {
        // The default color is black.
        String callerColor = " [color=black]";
        String calleeColor = " [color=black]";
        if (isUninterruptible(caller)) {
            callerColor = " [color=blue]";
            if (!isCalleeMustBe(caller)) {
                callerColor = " [color=orange]";
            }
        }
        if (isUninterruptible(callee)) {
            calleeColor = " [color=blue]";
            if (!isCalleeMustBe(callee)) {
                calleeColor = " [color=purple]";
            }
        } else {
            calleeColor = " [color=red]";
        }
        if (isNoTransitionCFunction(callee)) {
            calleeColor = " [color=green]";
        }
        System.out.println("/* DOT */    " + caller.format("<%h.%n>") + callerColor);
        System.out.println("/* DOT */    " + callee.format("<%h.%n>") + calleeColor);
        System.out.println("/* DOT */    " + caller.format("<%h.%n>") + " -> " + callee.format("<%h.%n>") + calleeColor);
    }

    public static class WarningException extends Exception {

        public WarningException(String message) {
            super(message);
        }

        /** Every exception needs a generated serialVersionUID. */
        private static final long serialVersionUID = -1407786075546990780L;
    }
}
