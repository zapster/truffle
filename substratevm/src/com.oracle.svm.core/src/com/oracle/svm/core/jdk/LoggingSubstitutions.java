/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jdk;

import java.util.HashMap;
import java.util.Map;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;

/*
 * Break the link between the {@link sun.util.logging.PlatformLogger} and {@link
 * java.util.logging.Logger}. This means we still have basic logging for the JDK classes to
 * System.err, but not the "fancy" logging facilities.
 */

@TargetClass(sun.util.logging.PlatformLogger.class)
final class Target_sun_util_logging_PlatformLogger {

    @Alias @RecomputeFieldValue(kind = Kind.FromAlias) private static Map<?, ?> loggers = new HashMap<>();
}

@TargetClass(sun.util.logging.LoggingSupport.class)
final class Target_sun_util_logging_LoggingSupport {

    /* before JDK 7 update 40 */
    @Alias @TargetElement(optional = true) @RecomputeFieldValue(kind = Kind.Reset)//
    private static sun.util.logging.LoggingProxy proxy;

    /* after JDK 7 update 40 */
    @Alias @TargetElement(optional = true) @RecomputeFieldValue(kind = Kind.NewInstance, declClassName = "sun.util.logging.PlatformLogger$DefaultLoggingProxy")//
    private static Object loggerProxy;
    @Alias @TargetElement(optional = true) @RecomputeFieldValue(kind = Kind.Reset)//
    private static Object javaLoggerProxy;
}

@TargetClass(value = sun.util.logging.PlatformLogger.class, innerClass = "LoggerProxy")
final class Target_sun_util_logging_PlatformLogger_LoggerProxy {

    @Alias private String name;

    /* before JDK 7 update 40 */
    @Substitute
    @TargetElement(optional = true)
    private String getCallerInfo() {
        return name;
    }
}

/** Dummy class to have a class with the file's name. */
public final class LoggingSubstitutions {
}
