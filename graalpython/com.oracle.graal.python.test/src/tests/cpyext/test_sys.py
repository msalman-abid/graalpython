# Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

import sys
from . import CPyExtTestCase, CPyExtFunction, unhandled_error_compare, GRAALPYTHON
__dir__ = __file__.rpartition("/")[0]

def _reference_get_object(args):
    try:
        return getattr(sys, args[0])
    except AttributeError:
        raise SystemError # raised by PyBuildValue(..., NULL)
        
class TestPySys(CPyExtTestCase):
    
    def compile_module(self, name):
        type(self).mro()[1].__dict__["test_%s" % name].create_module(name)
        super(TestPySys, self).compile_module(name)
        
    test_PySys_GetObject = CPyExtFunction(
        _reference_get_object,
        lambda: (
            ("hello",),
            ("byteorder",),
        ),
        resultspec="O",
        argspec='s',
        arguments=["char* name"],
        cmpfunc=unhandled_error_compare
    )

    test_Interpreters = CPyExtFunction(
        lambda args: 1,
        lambda: (
            (),
        ),
        code="""PyObject* wrap_Interpreters() {
            return PyLong_FromLong(PyThreadState_Get()->interp == PyInterpreterState_Main());
        }
        """,
        resultspec="O",
        argspec='',
        arguments=[],
        callfunction="wrap_Interpreters",
        cmpfunc=unhandled_error_compare,
    )
