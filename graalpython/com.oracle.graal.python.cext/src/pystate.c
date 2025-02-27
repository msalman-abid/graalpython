/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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

#include "capi.h"
#include <trufflenfi.h>

extern TruffleContext* TRUFFLE_CONTEXT;

PyThreadState *
_PyThreadState_UncheckedGet(void) {
    return GraalPyThreadState_Get();
}

void PyThreadState_Clear(PyThreadState *tstate) {
}

void PyThreadState_DeleteCurrent(void) {
}

int64_t PyInterpreterState_GetID(PyInterpreterState *interp)
{
    return 0;
}

int64_t PyInterpreterState_GetIDFromThreadState(PyThreadState *state) {
	return 0;
}

PyInterpreterState* PyInterpreterState_Main()
{
    // TODO: not yet supported
    return NULL;
}

PyThreadState* PyGILState_GetThisThreadState(void) {
    // TODO this should return NULL when called from a thread that is not known to python
    return GraalPyThreadState_Get();
}

PyObject* PyState_FindModule(struct PyModuleDef* module) {
    Py_ssize_t index = module->m_base.m_index;
    if (module->m_slots) {
        return NULL;
    } else if (index == 0) {
        return NULL;
    } else {
        return GraalPyTruffleState_FindModule(index);
    }
}

int PyState_AddModule(PyObject* module, struct PyModuleDef* def) {
    Py_ssize_t index;
    if (!def) {
        Py_FatalError("PyState_AddModule: Module Definition is NULL");
        return -1;
    }
    // TODO(fa): check if module was already added

    if (def->m_slots) {
        PyErr_SetString(PyExc_SystemError,
                        "PyState_AddModule called on module with slots");
        return -1;
    }

    // TODO(fa): implement
    return 0;
}

int PyState_RemoveModule(struct PyModuleDef* def) {
    Py_ssize_t index = def->m_base.m_index;
    if (def->m_slots) {
        PyErr_SetString(PyExc_SystemError,
                        "PyState_RemoveModule called on module with slots");
        return -1;
    }
    if (index == 0) {
        Py_FatalError("PyState_RemoveModule: Module index invalid.");
        return -1;
    }
    // TODO(fa): implement
    return 0;
}

#define _PYGILSTATE_LOCKED   0x1
#define _PYGILSTATE_ATTACHED 0x2

PyAPI_FUNC(PyGILState_STATE) PyGILState_Ensure() {
    int result = 0;
    if (TRUFFLE_CONTEXT) {
		if ((*TRUFFLE_CONTEXT)->getTruffleEnv(TRUFFLE_CONTEXT) == NULL) {
			(*TRUFFLE_CONTEXT)->attachCurrentThread(TRUFFLE_CONTEXT);
			result |= _PYGILSTATE_ATTACHED;
		}
    }
    int locked = GraalPyTruffleGILState_Ensure();
    if (locked) {
        result |= _PYGILSTATE_LOCKED;
    }
    return result;
}

PyAPI_FUNC(void) PyGILState_Release(PyGILState_STATE state) {
    if (state & _PYGILSTATE_LOCKED) {
        GraalPyTruffleGILState_Release();
    }
    if (TRUFFLE_CONTEXT && (state & _PYGILSTATE_ATTACHED)) {
        (*TRUFFLE_CONTEXT)->detachCurrentThread(TRUFFLE_CONTEXT);
    }
}
