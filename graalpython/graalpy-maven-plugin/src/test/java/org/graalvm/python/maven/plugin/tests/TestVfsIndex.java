/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.python.maven.plugin.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class TestVfsIndex extends GraalPyPluginTests {
    @Test
    public void testVfsIndex() throws Exception {
        Assumptions.assumeTrue(CAN_RUN_TESTS);
        var v = getLocalVerifier("list_files_test");
        var vfsPath = Path.of(v.getBasedir(), "target", "classes", "vfs");
        v.addCliArguments("process-resources");
        v.execute();
        var vfsList = vfsPath.resolve("fileslist.txt");
        assertTrue(Files.exists(vfsList));
        var lines = new HashSet<String>(Files.readAllLines(vfsList));
        var linesStr = String.join("\n", lines);
        assertTrue(lines.contains("/vfs/"), linesStr);
        assertTrue(lines.contains("/vfs/home/"), linesStr);
        assertTrue(lines.contains("/vfs/home/dir_with_file/"), linesStr);
        assertTrue(lines.contains("/vfs/home/dir_with_file/file.txt"), linesStr);
        assertFalse(lines.contains("/vfs/home/dir_with_file/empty_dir/"), linesStr);
        assertFalse(lines.contains("/vfs/home/empty_dir/"), linesStr);
        assertFalse(lines.contains("/vfs/home/empty_dir/empty_dir/"), linesStr);
        assertEquals(4, lines.size(), linesStr);
    }
}
