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
package com.oracle.graal.python.builtins.objects.cext.capi;

import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_PTR_ADD;
import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_PTR_COMPARE;
import static com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol.FUN_PY_TRUFFLE_MEMORYVIEW_FROM_OBJECT;
import static com.oracle.graal.python.builtins.objects.cext.structs.CConstants.PYLONG_BITS_IN_DIGIT;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyFloatObject__ob_fval;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyMethodDef__ml_doc;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyMethodDef__ml_flags;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyMethodDef__ml_meth;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyMethodDef__ml_name;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyModuleDef_Slot__slot;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyModuleDef_Slot__value;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyModuleDef__m_doc;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyModuleDef__m_methods;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyModuleDef__m_size;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyModuleDef__m_slots;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyObject__ob_type;
import static com.oracle.graal.python.builtins.objects.cext.structs.CFields.PyTypeObject__tp_as_buffer;
import static com.oracle.graal.python.nodes.SpecialMethodNames.T___COMPLEX__;
import static com.oracle.graal.python.nodes.StringLiterals.J_NFI_LANGUAGE;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.SystemError;
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;
import static com.oracle.graal.python.util.PythonUtils.toTruffleStringUncached;
import static com.oracle.graal.python.util.PythonUtils.tsLiteral;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.Python3Core;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.graal.python.builtins.objects.bytes.PByteArray;
import com.oracle.graal.python.builtins.objects.bytes.PBytes;
import com.oracle.graal.python.builtins.objects.cext.PythonAbstractNativeObject;
import com.oracle.graal.python.builtins.objects.cext.PythonNativeClass;
import com.oracle.graal.python.builtins.objects.cext.PythonNativeObject;
import com.oracle.graal.python.builtins.objects.cext.PythonNativeVoidPtr;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.AsCharPointerNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.CreateFunctionNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.FromCharPointerNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.ResolveHandleNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.ExternalFunctionNodes.DefaultCheckFunctionResultNode;
import com.oracle.graal.python.builtins.objects.cext.capi.ExternalFunctionNodes.PExternalFunctionWrapper;
import com.oracle.graal.python.builtins.objects.cext.capi.PyTruffleObjectFree.FreeNode;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.HandlePointerConverter;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.HandleResolver;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.NativeToPythonNode;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.NativeToPythonStealingNode;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.PythonToNativeNode;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitionsFactory.NativeToPythonNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitionsFactory.PythonToNativeNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.GetNativeWrapperNode;
import com.oracle.graal.python.builtins.objects.cext.common.CArrayWrappers.CArrayWrapper;
import com.oracle.graal.python.builtins.objects.cext.common.CArrayWrappers.CByteArrayWrapper;
import com.oracle.graal.python.builtins.objects.cext.common.CArrayWrappers.CStringWrapper;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.CheckFunctionResultNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.EnsureTruffleStringNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.ImportCExtSymbolNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtContext;
import com.oracle.graal.python.builtins.objects.cext.common.CExtContext.ModuleSpec;
import com.oracle.graal.python.builtins.objects.cext.common.GetNextVaArgNode;
import com.oracle.graal.python.builtins.objects.cext.common.NativeCExtSymbol;
import com.oracle.graal.python.builtins.objects.cext.structs.CFields;
import com.oracle.graal.python.builtins.objects.cext.structs.CStructAccess;
import com.oracle.graal.python.builtins.objects.cext.structs.CStructs;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes.ToArrayNode;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.builtins.objects.floats.PFloat;
import com.oracle.graal.python.builtins.objects.function.PBuiltinFunction;
import com.oracle.graal.python.builtins.objects.function.PKeyword;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.memoryview.PMemoryView;
import com.oracle.graal.python.builtins.objects.method.PBuiltinMethod;
import com.oracle.graal.python.builtins.objects.module.ModuleGetNameNode;
import com.oracle.graal.python.builtins.objects.module.PythonModule;
import com.oracle.graal.python.builtins.objects.object.PythonObject;
import com.oracle.graal.python.builtins.objects.str.PString;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.builtins.objects.type.PythonAbstractClass;
import com.oracle.graal.python.builtins.objects.type.PythonBuiltinClass;
import com.oracle.graal.python.builtins.objects.type.PythonClass;
import com.oracle.graal.python.builtins.objects.type.PythonManagedClass;
import com.oracle.graal.python.builtins.objects.type.TypeBuiltins;
import com.oracle.graal.python.builtins.objects.type.TypeNodes;
import com.oracle.graal.python.builtins.objects.type.TypeNodes.GetBaseClassNode;
import com.oracle.graal.python.builtins.objects.type.TypeNodes.GetMroStorageNode;
import com.oracle.graal.python.builtins.objects.type.TypeNodes.ProfileClassNode;
import com.oracle.graal.python.lib.PyFloatAsDoubleNode;
import com.oracle.graal.python.lib.PyNumberAsSizeNode;
import com.oracle.graal.python.lib.PyObjectLookupAttr;
import com.oracle.graal.python.lib.PyObjectSizeNode;
import com.oracle.graal.python.nodes.BuiltinNames;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.PNodeWithContext;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.SpecialAttributeNames;
import com.oracle.graal.python.nodes.SpecialMethodNames;
import com.oracle.graal.python.nodes.attributes.ReadAttributeFromObjectNode;
import com.oracle.graal.python.nodes.attributes.WriteAttributeToDynamicObjectNode;
import com.oracle.graal.python.nodes.attributes.WriteAttributeToObjectNode;
import com.oracle.graal.python.nodes.call.CallNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallUnaryNode.LookupAndCallUnaryDynamicNode;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.frame.GetCurrentFrameRef;
import com.oracle.graal.python.nodes.object.GetClassNode;
import com.oracle.graal.python.nodes.object.GetClassNode.GetPythonObjectClassNode;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToJavaStringNode;
import com.oracle.graal.python.nodes.util.CastToJavaStringNodeGen;
import com.oracle.graal.python.nodes.util.CastToTruffleStringNode;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonContext.GetThreadStateNode;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.exception.PythonErrorType;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.runtime.sequence.storage.MroSequenceStorage;
import com.oracle.graal.python.util.Function;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.EncapsulatingNodeReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleString.Encoding;
import com.oracle.truffle.nfi.api.SignatureLibrary;

public abstract class CExtNodes {

    private static final String J_UNICODE = "unicode";
    private static final String J_SUBTYPE_NEW = "_subtype_new";

    /**
     * For some builtin classes, the CPython approach to creating a subclass instance is to just
     * call the alloc function and then assign some fields. This needs to be done in C. This node
     * will call that subtype C function with two arguments, the C type object and an object
     * argument to fill in from.
     */
    @ImportStatic({PGuards.class})
    @GenerateInline(false) // footprint reduction 44 -> 25
    public abstract static class SubtypeNew extends Node {

        /**
         * tget the <code>typename_subtype_new</code> function
         */
        protected NativeCAPISymbol getFunction() {
            throw CompilerDirectives.shouldNotReachHere();
        }

        protected abstract Object execute(Object object, Object arg);

        protected static NativeCAPISymbol getFunction(String typenamePrefix) {
            CompilerAsserts.neverPartOfCompilation();
            String subtypeNewFunctionName = typenamePrefix + J_SUBTYPE_NEW;
            NativeCAPISymbol result = NativeCAPISymbol.getByName(subtypeNewFunctionName);
            assert result != null : "SubtypeNew function not found: " + subtypeNewFunctionName;
            return result;
        }

        @Specialization
        Object callNativeConstructor(Object object, Object arg,
                        @Bind("this") Node inliningTarget,
                        @Cached PythonToNativeNode toSulongNode,
                        @Cached NativeToPythonNode toJavaNode,
                        @CachedLibrary(limit = "1") InteropLibrary interopLibrary,
                        @Cached ImportCExtSymbolNode importCAPISymbolNode) {
            assert TypeNodes.NeedsNativeAllocationNode.executeUncached(object);
            try {
                CApiContext cApiContext = PythonContext.get(inliningTarget).getCApiContext();
                Object result = interopLibrary.execute(importCAPISymbolNode.execute(inliningTarget, cApiContext, getFunction()), toSulongNode.execute(object), arg);
                return toJavaNode.execute(result);
            } catch (UnsupportedMessageException | UnsupportedTypeException | ArityException e) {
                throw CompilerDirectives.shouldNotReachHere("C subtype_new function failed", e);
            }
        }
    }

    @GenerateInline(false) // footprint reduction 44 -> 25
    public abstract static class FloatSubtypeNew extends SubtypeNew {

        private static final NativeCAPISymbol NEW_FUNCTION = getFunction(BuiltinNames.J_FLOAT);

        @Override
        protected final NativeCAPISymbol getFunction() {
            return NEW_FUNCTION;
        }

        public final Object call(Object object, double arg) {
            return execute(object, arg);
        }

        public static FloatSubtypeNew create() {
            return CExtNodesFactory.FloatSubtypeNewNodeGen.create();
        }
    }

    public abstract static class TupleSubtypeNew extends SubtypeNew {

        private static final NativeCAPISymbol NEW_FUNCTION = getFunction(BuiltinNames.J_TUPLE);

        @Child private PythonToNativeNode toSulongNode;

        @Override
        protected final NativeCAPISymbol getFunction() {
            return NEW_FUNCTION;
        }

        public final Object call(Object object, Object arg) {
            if (toSulongNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toSulongNode = insert(PythonToNativeNodeGen.create());
            }
            return execute(object, toSulongNode.execute(arg));
        }

        @NeverDefault
        public static TupleSubtypeNew create() {
            return CExtNodesFactory.TupleSubtypeNewNodeGen.create();
        }
    }

    public abstract static class StringSubtypeNew extends SubtypeNew {

        private static final NativeCAPISymbol NEW_FUNCTION = getFunction(J_UNICODE);

        @Child private PythonToNativeNode toSulongNode;

        @Override
        protected final NativeCAPISymbol getFunction() {
            return NEW_FUNCTION;
        }

        public final Object call(Object object, Object arg) {
            if (toSulongNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toSulongNode = insert(PythonToNativeNodeGen.create());
            }
            return execute(object, toSulongNode.execute(arg));
        }

        public static StringSubtypeNew create() {
            return CExtNodesFactory.StringSubtypeNewNodeGen.create();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    @GenerateInline(false) // footprint reduction 40 -> 21
    public abstract static class FromNativeSubclassNode extends Node {

        public abstract Double execute(VirtualFrame frame, PythonAbstractNativeObject object);

        @Specialization
        static Double doDouble(VirtualFrame frame, PythonAbstractNativeObject object,
                        @Bind("this") Node inliningTarget,
                        @Cached GetPythonObjectClassNode getClass,
                        @Cached IsSubtypeNode isSubtype,
                        @Cached CStructAccess.ReadDoubleNode read) {
            if (isFloatSubtype(frame, inliningTarget, object, getClass, isSubtype)) {
                return read.readFromObj(object, PyFloatObject__ob_fval);
            }
            return null;
        }

        public static boolean isFloatSubtype(VirtualFrame frame, Node inliningTarget, Object object, GetClassNode getClass, IsSubtypeNode isSubtype) {
            return isSubtype.execute(frame, getClass.execute(inliningTarget, object), PythonBuiltinClassType.PFloat);
        }

        public static boolean isFloatSubtype(VirtualFrame frame, Node inliningTarget, PythonAbstractNativeObject object, GetPythonObjectClassNode getClass, IsSubtypeNode isSubtype) {
            return isSubtype.execute(frame, getClass.execute(inliningTarget, object), PythonBuiltinClassType.PFloat);
        }

        @NeverDefault
        public static FromNativeSubclassNode create() {
            return CExtNodesFactory.FromNativeSubclassNodeGen.create();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    /**
     * Materializes a primitive value of a primitive native wrapper to ensure pointer equality.
     */
    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    @ImportStatic(CApiGuards.class)
    public abstract static class MaterializeDelegateNode extends Node {

        public abstract Object execute(Node inliningTarget, PythonNativeWrapper object);

        @Specialization(guards = {"!isMaterialized(object)", "object.isBool()"})
        static PInt doBoolNativeWrapper(Node inliningTarget, PrimitiveNativeWrapper object) {
            // Special case for True and False: use singletons
            Python3Core core = PythonContext.get(inliningTarget);
            PInt materializedInt = object.getBool() ? core.getTrue() : core.getFalse();
            object.setMaterializedObject(materializedInt);

            // If the singleton already has a native wrapper, we may need to update the pointer
            // of wrapper 'object' since the native could code see the same pointer.
            if (materializedInt.getNativeWrapper() != null) {
                object.setNativePointer(materializedInt.getNativeWrapper().getNativePointer());
            } else {
                materializedInt.setNativeWrapper(object);
            }
            return materializedInt;
        }

        @Specialization(guards = {"!isMaterialized(object)", "object.isInt()"})
        static PInt doIntNativeWrapper(PrimitiveNativeWrapper object,
                        @Shared("factory") @Cached(inline = false) PythonObjectFactory factory) {
            PInt materializedInt = factory.createInt(object.getInt());
            object.setMaterializedObject(materializedInt);
            materializedInt.setNativeWrapper(object);
            return materializedInt;
        }

        @Specialization(guards = {"!isMaterialized(object)", "object.isLong()"})
        static PInt doLongNativeWrapper(PrimitiveNativeWrapper object,
                        @Shared("factory") @Cached(inline = false) PythonObjectFactory factory) {
            PInt materializedInt = factory.createInt(object.getLong());
            object.setMaterializedObject(materializedInt);
            materializedInt.setNativeWrapper(object);
            return materializedInt;
        }

        @Specialization(guards = {"!isMaterialized(object)", "object.isDouble()", "!isNaN(object)"})
        static PFloat doDoubleNativeWrapper(PrimitiveNativeWrapper object,
                        @Shared("factory") @Cached(inline = false) PythonObjectFactory factory) {
            PFloat materializedInt = factory.createFloat(object.getDouble());
            materializedInt.setNativeWrapper(object);
            object.setMaterializedObject(materializedInt);
            return materializedInt;
        }

        @Specialization(guards = {"!isMaterialized(object)", "object.isDouble()", "isNaN(object)"})
        static PFloat doDoubleNativeWrapperNaN(Node inliningTarget, PrimitiveNativeWrapper object) {
            // Special case for double NaN: use singleton
            PFloat materializedFloat = PythonContext.get(inliningTarget).getNaN();
            object.setMaterializedObject(materializedFloat);

            // If the NaN singleton already has a native wrapper, we may need to update the
            // pointer
            // of wrapper 'object' since the native code should see the same pointer.
            if (materializedFloat.getNativeWrapper() != null) {
                object.setNativePointer(materializedFloat.getNativeWrapper().getNativePointer());
            } else {
                materializedFloat.setNativeWrapper(object);
            }
            return materializedFloat;
        }

        @Specialization(guards = "isMaterialized(object)")
        static Object doMaterialized(PrimitiveNativeWrapper object) {
            return object.getDelegate();
        }

        @Specialization(guards = "!isPrimitiveNativeWrapper(object)")
        static Object doNativeWrapperGeneric(PythonNativeWrapper object) {
            return object.getDelegate();
        }

        protected static boolean isPrimitiveNativeWrapper(PythonNativeWrapper object) {
            return object instanceof PrimitiveNativeWrapper;
        }

        protected static boolean isNaN(PrimitiveNativeWrapper object) {
            assert object.isDouble();
            return Double.isNaN(object.getDouble());
        }

        static boolean isMaterialized(PrimitiveNativeWrapper wrapper) {
            return wrapper.getDelegate() != null;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 60 -> 41
    public abstract static class AsCharPointerNode extends Node {
        public abstract Object execute(Object obj, boolean allocatePyMem);

        public final Object execute(Object obj) {
            return execute(obj, false);
        }

        @Specialization
        static Object doPString(PString str, boolean allocatePyMem,
                        @Bind("this") Node inliningTarget,
                        @Cached CastToTruffleStringNode castToStringNode,
                        @Shared @Cached TruffleString.CopyToByteArrayNode toBytes,
                        @Shared @Cached TruffleString.SwitchEncodingNode switchEncoding,
                        @Shared @Cached CStructAccess.AllocateNode alloc,
                        @Shared @Cached CStructAccess.WriteByteNode write) {
            TruffleString value = castToStringNode.execute(inliningTarget, str);
            byte[] bytes = toBytes.execute(switchEncoding.execute(value, Encoding.UTF_8), Encoding.UTF_8);
            Object mem = alloc.alloc(bytes.length + 1, allocatePyMem);
            write.writeByteArray(mem, bytes);
            return mem;
        }

        @Specialization
        static Object doString(TruffleString str, boolean allocatePyMem,
                        @Shared @Cached TruffleString.CopyToByteArrayNode toBytes,
                        @Shared @Cached TruffleString.SwitchEncodingNode switchEncoding,
                        @Shared @Cached CStructAccess.AllocateNode alloc,
                        @Shared @Cached CStructAccess.WriteByteNode write) {
            byte[] bytes = toBytes.execute(switchEncoding.execute(str, Encoding.UTF_8), Encoding.UTF_8);
            Object mem = alloc.alloc(bytes.length + 1, allocatePyMem);
            write.writeByteArray(mem, bytes);
            return mem;
        }

        @Specialization
        static Object doBytes(PBytes bytes, boolean allocatePyMem,
                        @Bind("this") Node inliningTarget,
                        @Shared @Cached SequenceStorageNodes.ToByteArrayNode toBytesNode,
                        @Shared @Cached CStructAccess.AllocateNode alloc,
                        @Shared @Cached CStructAccess.WriteByteNode write) {
            return doByteArray(toBytesNode.execute(inliningTarget, bytes.getSequenceStorage()), allocatePyMem, alloc, write);
        }

        @Specialization
        static Object doBytes(PByteArray bytes, boolean allocatePyMem,
                        @Bind("this") Node inliningTarget,
                        @Shared @Cached SequenceStorageNodes.ToByteArrayNode toBytesNode,
                        @Shared @Cached CStructAccess.AllocateNode alloc,
                        @Shared @Cached CStructAccess.WriteByteNode write) {
            return doByteArray(toBytesNode.execute(inliningTarget, bytes.getSequenceStorage()), allocatePyMem, alloc, write);
        }

        @Specialization
        static Object doByteArray(byte[] arr, boolean allocatePyMem,
                        @Shared @Cached CStructAccess.AllocateNode alloc,
                        @Shared @Cached CStructAccess.WriteByteNode write) {
            Object mem = alloc.alloc(arr.length + 1, allocatePyMem);
            write.writeByteArray(mem, arr);
            return mem;
        }

        public static AsCharPointerNode getUncached() {
            return AsCharPointerNodeGen.getUncached();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 36 -> 17
    public abstract static class FromCharPointerNode extends Node {
        public final TruffleString execute(Object charPtr) {
            return execute(charPtr, true);
        }

        public abstract TruffleString execute(Object charPtr, boolean copy);

        @Specialization
        static TruffleString doCStringWrapper(CStringWrapper cStringWrapper, @SuppressWarnings("unused") boolean copy) {
            return cStringWrapper.getString();
        }

        @Specialization
        static TruffleString doCByteArrayWrapper(CByteArrayWrapper cByteArrayWrapper, boolean copy,
                        @Shared @Cached TruffleString.FromByteArrayNode fromBytes,
                        @Shared("switchEncoding") @Cached TruffleString.SwitchEncodingNode switchEncodingNode) {
            CompilerAsserts.partialEvaluationConstant(copy);
            byte[] byteArray = cByteArrayWrapper.getByteArray();
            return switchEncodingNode.execute(fromBytes.execute(byteArray, 0, byteArray.length, Encoding.UTF_8, copy), TS_ENCODING);
        }

        @Specialization(guards = "!isCArrayWrapper(charPtr)", limit = "3")
        static TruffleString doPointer(Object charPtr, boolean copy,
                        @Cached CStructAccess.ReadByteNode read,
                        @CachedLibrary("charPtr") InteropLibrary lib,
                        @Cached TruffleString.FromNativePointerNode fromNative,
                        @Shared @Cached TruffleString.FromByteArrayNode fromBytes,
                        @Shared("switchEncoding") @Cached TruffleString.SwitchEncodingNode switchEncodingNode) {

            int length = 0;
            while (read.readArrayElement(charPtr, length) != 0) {
                length++;
            }

            if (lib.isPointer(charPtr)) {
                return switchEncodingNode.execute(fromNative.execute(charPtr, 0, length, Encoding.UTF_8, copy), TS_ENCODING);
            }
            byte[] result = read.readByteArray(charPtr, length);
            return switchEncodingNode.execute(fromBytes.execute(result, Encoding.UTF_8, false), TS_ENCODING);
        }

        static boolean isCArrayWrapper(Object object) {
            return object instanceof CArrayWrapper || object instanceof PySequenceArrayWrapper;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class GetNativeClassNode extends PNodeWithContext {

        public abstract Object execute(Node inliningTarget, PythonAbstractNativeObject object);

        @Specialization
        static Object getNativeClass(Node inliningTarget, PythonAbstractNativeObject object,
                        @Cached(inline = false) CStructAccess.ReadObjectNode callGetObTypeNode,
                        @Cached ProfileClassNode classProfile) {
            // do not convert wrap 'object.object' since that is really the native pointer object
            return classProfile.profile(inliningTarget, callGetObTypeNode.readFromObj(object, PyObject__ob_type));
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 68 -> 50
    public abstract static class PointerCompareNode extends Node {
        public abstract boolean execute(TruffleString opName, Object a, Object b);

        private static boolean executeCFunction(Node inliningTarget, int op, Object a, Object b, InteropLibrary interopLibrary, ImportCExtSymbolNode importCAPISymbolNode) {
            try {
                Object sym = importCAPISymbolNode.execute(inliningTarget, PythonContext.get(inliningTarget).getCApiContext(), FUN_PTR_COMPARE);
                return (int) interopLibrary.execute(sym, a, b, op) != 0;
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException(FUN_PTR_COMPARE + " didn't work!");
            }
        }

        @Specialization(guards = "isEq(opName, equalNode)", limit = "2")
        static boolean doEq(@SuppressWarnings("unused") TruffleString opName, PythonAbstractNativeObject a, PythonAbstractNativeObject b,
                        @CachedLibrary("a") InteropLibrary aLib,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary bLib,
                        @Shared @Cached @SuppressWarnings("unused") TruffleString.EqualNode equalNode) {
            return aLib.isIdentical(a, b, bLib);
        }

        @Specialization(guards = "isNe(opName, equalNode)", limit = "2")
        static boolean doNe(@SuppressWarnings("unused") TruffleString opName, PythonAbstractNativeObject a, PythonAbstractNativeObject b,
                        @CachedLibrary("a") InteropLibrary aLib,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary bLib,
                        @Shared @Cached @SuppressWarnings("unused") TruffleString.EqualNode equalNode) {
            return !aLib.isIdentical(a, b, bLib);
        }

        @Specialization(guards = "areEqual(cachedOpName, opName, equalNode)")
        static boolean doPythonNativeObject(@SuppressWarnings("unused") TruffleString opName, PythonNativeObject a, PythonNativeObject b,
                        @Bind("this") Node inliningTarget,
                        @Shared @Cached @SuppressWarnings("unused") TruffleString.EqualNode equalNode,
                        @Shared @Cached(value = "opName", neverDefault = true) @SuppressWarnings("unused") TruffleString cachedOpName,
                        @Cached(value = "findOp(opName, equalNode)", allowUncached = true, neverDefault = false) int op,
                        @Shared @CachedLibrary(limit = "1") InteropLibrary interopLibrary,
                        @Shared @Cached ImportCExtSymbolNode importCAPISymbolNode) {
            return executeCFunction(inliningTarget, op, a.getPtr(), b.getPtr(), interopLibrary, importCAPISymbolNode);
        }

        @Specialization(guards = "areEqual(cachedOpName, opName, equalNode)")
        static boolean doPythonNativeObjectLong(@SuppressWarnings("unused") TruffleString opName, PythonNativeObject a, long b,
                        @Bind("this") Node inliningTarget,
                        @Shared @Cached @SuppressWarnings("unused") TruffleString.EqualNode equalNode,
                        @Shared @Cached(value = "opName", neverDefault = true) @SuppressWarnings("unused") TruffleString cachedOpName,
                        @Cached(value = "findOp(opName, equalNode)", allowUncached = true, neverDefault = false) int op,
                        @Shared @CachedLibrary(limit = "1") InteropLibrary interopLibrary,
                        @Shared @Cached ImportCExtSymbolNode importCAPISymbolNode) {
            return executeCFunction(inliningTarget, op, a.getPtr(), b, interopLibrary, importCAPISymbolNode);
        }

        @Specialization(guards = "areEqual(cachedOpName, opName, equalNode)")
        static boolean doNativeVoidPtrLong(@SuppressWarnings("unused") TruffleString opName, PythonNativeVoidPtr a, long b,
                        @Bind("this") Node inliningTarget,
                        @Shared @Cached @SuppressWarnings("unused") TruffleString.EqualNode equalNode,
                        @Shared @Cached(value = "opName", neverDefault = true) @SuppressWarnings("unused") TruffleString cachedOpName,
                        @Cached(value = "findOp(opName, equalNode)", allowUncached = true, neverDefault = false) int op,
                        @Shared @CachedLibrary(limit = "1") InteropLibrary interopLibrary,
                        @Shared @Cached ImportCExtSymbolNode importCAPISymbolNode) {
            return executeCFunction(inliningTarget, op, a.getPointerObject(), b, interopLibrary, importCAPISymbolNode);
        }

        static int findOp(TruffleString specialMethodName, TruffleString.EqualNode equalNode) {
            for (int i = 0; i < SpecialMethodNames.COMPARE_OP_COUNT; i++) {
                if (equalNode.execute(SpecialMethodNames.getCompareName(i), specialMethodName, TS_ENCODING)) {
                    return i;
                }
            }
            throw new RuntimeException("The special method used for Python C API pointer comparison must be a constant literal (i.e., interned) string");
        }

        static boolean areEqual(TruffleString cachedOpName, TruffleString opName, TruffleString.EqualNode equalNode) {
            return equalNode.execute(cachedOpName, opName, TS_ENCODING);
        }

        static boolean isEq(TruffleString opName, TruffleString.EqualNode equalNode) {
            return equalNode.execute(SpecialMethodNames.T___EQ__, opName, TS_ENCODING);
        }

        static boolean isNe(TruffleString opName, TruffleString.EqualNode equalNode) {
            return equalNode.execute(SpecialMethodNames.T___NE__, opName, TS_ENCODING);
        }
    }

    @TruffleBoundary
    public static Object pointerAdd(Object pointer, long offset) {
        return PCallCapiFunction.getUncached().call(FUN_PTR_ADD, pointer, offset);
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Converts a Python object to a
     * {@link com.oracle.graal.python.builtins.objects.complex.PComplex} .<br/>
     * This node is, for example, used to implement {@code PyComplex_AsCComplex} and does coercion
     * and may raise a Python exception if coercion fails.
     */
    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    @ImportStatic(SpecialMethodNames.class)
    public abstract static class AsNativeComplexNode extends PNodeWithContext {
        public abstract PComplex execute(Node inliningTarget, boolean arg);

        public abstract PComplex execute(Node inliningTarget, int arg);

        public abstract PComplex execute(Node inliningTarget, long arg);

        public abstract PComplex execute(Node inliningTarget, double arg);

        public abstract PComplex execute(Node inliningTarget, Object arg);

        @Specialization
        static PComplex doPComplex(PComplex value) {
            return value;
        }

        @Specialization
        static PComplex doBoolean(boolean value,
                        @Shared @Cached(inline = false) PythonObjectFactory factory) {
            return factory.createComplex(value ? 1.0 : 0.0, 0.0);
        }

        @Specialization
        static PComplex doInt(int value,
                        @Shared @Cached(inline = false) PythonObjectFactory factory) {
            return factory.createComplex(value, 0.0);
        }

        @Specialization
        static PComplex doLong(long value,
                        @Shared @Cached(inline = false) PythonObjectFactory factory) {
            return factory.createComplex(value, 0.0);
        }

        @Specialization
        PComplex doDouble(double value,
                        @Shared @Cached(inline = false) PythonObjectFactory factory) {
            return factory.createComplex(value, 0.0);
        }

        @Specialization
        static PComplex doPInt(PInt value,
                        @Shared @Cached(inline = false) PythonObjectFactory factory) {
            return factory.createComplex(value.doubleValue(), 0.0);
        }

        @Specialization
        static PComplex doPFloat(PFloat value,
                        @Shared @Cached(inline = false) PythonObjectFactory factory) {
            return factory.createComplex(value.getValue(), 0.0);
        }

        @Specialization(replaces = {"doPComplex", "doBoolean", "doInt", "doLong", "doDouble", "doPInt", "doPFloat"})
        static PComplex runGeneric(Node inliningTarget, Object value,
                        @Cached PyFloatAsDoubleNode asDoubleNode,
                        @Cached(inline = false) LookupAndCallUnaryDynamicNode callComplex,
                        @Shared @Cached(inline = false) PythonObjectFactory factory,
                        @Cached PRaiseNode.Lazy raiseNode) {
            Object result = callComplex.executeObject(value, T___COMPLEX__);
            // TODO(fa) according to CPython's 'PyComplex_AsCComplex', they still allow subclasses
            // of PComplex
            if (result != PNone.NO_VALUE) {
                if (result instanceof PComplex) {
                    return (PComplex) result;
                } else {
                    throw raiseNode.get(inliningTarget).raise(PythonErrorType.TypeError, ErrorMessages.COMPLEX_RETURNED_NON_COMPLEX, value);
                }
            } else {
                return factory.createComplex(asDoubleNode.execute(null, inliningTarget, value), 0.0);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Casts a Python object to a Java long value without doing any coercion, i.e., it does not call
     * any magic method like {@code __index__} or {@code __int__}.<br/>
     * The semantics is like a Java type cast and therefore lossy.<br/>
     * As an optimization, this node can also unwrap {@code PrimitiveNativeWrapper} instances to
     * avoid eager and explicit conversion.
     */
    @ImportStatic(PythonUtils.class)
    @GenerateInline(inlineByDefault = true)
    @GenerateCached
    @GenerateUncached
    public abstract static class CastToNativeLongNode extends PNodeWithContext {
        public abstract long execute(Node inliningTarget, boolean arg);

        public abstract long execute(Node inliningTarget, byte arg);

        public abstract long execute(Node inliningTarget, int arg);

        public abstract long execute(Node inliningTarget, long arg);

        public abstract long execute(Node inliningTarget, double arg);

        public abstract Object execute(Node inliningTarget, Object arg);

        @Specialization(guards = "lengthNode.execute(value, TS_ENCODING) == 1", limit = "1")
        static long doString(TruffleString value,
                        @Cached(inline = false) TruffleString.CodePointAtIndexNode codepointAtIndexNode,
                        @SuppressWarnings("unused") @Cached(inline = false) TruffleString.CodePointLengthNode lengthNode) {
            return codepointAtIndexNode.execute(value, 0, TS_ENCODING);
        }

        @Specialization
        static long doBoolean(boolean value) {
            return value ? 1 : 0;
        }

        @Specialization
        static long doByte(byte value) {
            return value;
        }

        @Specialization
        static long doInt(int value) {
            return value;
        }

        @Specialization
        static long doLong(long value) {
            return value;
        }

        @Specialization
        static long doDouble(double value) {
            return (long) value;
        }

        @Specialization
        static long doPInt(PInt value) {
            return value.longValue();
        }

        @Specialization
        static long doPFloat(PFloat value) {
            return (long) value.getValue();
        }

        @Specialization
        static Object doPythonNativeVoidPtr(PythonNativeVoidPtr object) {
            return object.getPointerObject();
        }

        @Specialization(guards = "!object.isDouble()")
        static long doLongNativeWrapper(PrimitiveNativeWrapper object) {
            return object.getLong();
        }

        @Specialization(guards = "object.isDouble()")
        static long doDoubleNativeWrapper(PrimitiveNativeWrapper object) {
            return (long) object.getDouble();
        }

        @Specialization
        static Object run(Node inliningTarget, PythonNativeWrapper value,
                        @Cached(inline = false) CastToNativeLongNode recursive) {
            // TODO(fa) this specialization should eventually go away
            return recursive.execute(inliningTarget, value.getDelegate());
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 40 -> 21
    public abstract static class PCallCapiFunction extends Node {

        public static Object callUncached(NativeCAPISymbol symbol, Object... args) {
            return PCallCapiFunction.getUncached().execute(symbol, args);
        }

        public final Object call(NativeCAPISymbol symbol, Object... args) {
            return execute(symbol, args);
        }

        protected abstract Object execute(NativeCAPISymbol symbol, Object[] args);

        @Specialization
        static Object doWithoutContext(NativeCAPISymbol name, Object[] args,
                        @Bind("this") Node inliningTarget,
                        @Cached ImportCExtSymbolNode importCExtSymbolNode,
                        @CachedLibrary(limit = "1") InteropLibrary interopLibrary,
                        @Cached EnsureTruffleStringNode ensureTruffleStringNode) {
            try {
                CApiContext cApiContext = PythonContext.get(inliningTarget).getCApiContext();
                // TODO review EnsureTruffleStringNode with GR-37896
                return ensureTruffleStringNode.execute(inliningTarget, interopLibrary.execute(importCExtSymbolNode.execute(inliningTarget, cApiContext, name), args));
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                // consider these exceptions to be fatal internal errors
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }

        @NeverDefault
        public static PCallCapiFunction create() {
            return CExtNodesFactory.PCallCapiFunctionNodeGen.create();
        }

        public static PCallCapiFunction getUncached() {
            return CExtNodesFactory.PCallCapiFunctionNodeGen.getUncached();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Use this node to lookup a native type member like {@code tp_alloc}.<br>
     * <p>
     * This node basically implements the native member inheritance that is done by
     * {@code inherit_special} or other code in {@code PyType_Ready}. In addition, we do a special
     * case for special slots assignment that happens within {@Code type_new_alloc} for heap types.
     * </p>
     * <p>
     * Since it may be that a managed types needs to emulate such members but there is no
     * corresponding Python attribute (e.g. {@code tp_vectorcall_offset}), such members are stored
     * as hidden keys on the managed type. However, the MRO may contain native types and in this
     * case, we need to access the native member.
     * </p>
     */
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 44 -> 27
    public abstract static class LookupNativeMemberInMRONode extends Node {

        public abstract Object execute(Object cls, CFields nativeMemberName, HiddenKey managedMemberName);

        static boolean isSpecialHeapSlot(Object cls, HiddenKey key) {
            return cls instanceof PythonClass && (key == TypeBuiltins.TYPE_ALLOC || key == TypeBuiltins.TYPE_DEL);
            // mq: not supported yet
            // key == TypeBuiltins.TYPE_DEALLOC (subtype_dealloc)
            // key == TypeBuiltins.TYPE_TRAVERSE (subtype_traverse)
            // key == TypeBuiltins.TYPE_CLEAR (subtype_clear)
        }

        @Specialization(guards = "!isSpecialHeapSlot(cls, managedMemberName)")
        static Object doSingleContext(Object cls, CFields nativeMemberName, HiddenKey managedMemberName,
                        @Bind("this") Node inliningTarget,
                        @Cached GetMroStorageNode getMroNode,
                        @Cached SequenceStorageNodes.GetItemDynamicNode getItemNode,
                        @Shared @Cached("createForceType()") ReadAttributeFromObjectNode readAttrNode,
                        @Cached CStructAccess.ReadPointerNode getTypeMemberNode,
                        @CachedLibrary(limit = "3") InteropLibrary lib) {

            MroSequenceStorage mroStorage = getMroNode.execute(inliningTarget, cls);
            int n = mroStorage.length();

            for (int i = 0; i < n; i++) {
                PythonAbstractClass mroCls = (PythonAbstractClass) getItemNode.execute(inliningTarget, mroStorage, i);
                if (PGuards.isManagedClass(mroCls)) {
                    Object result = readAttrNode.execute(mroCls, managedMemberName);
                    if (result != PNone.NO_VALUE) {
                        return result;
                    }
                } else {
                    assert PGuards.isNativeClass(mroCls) : "invalid class inheritance structure; expected native class";
                    Object result = getTypeMemberNode.readFromObj((PythonNativeClass) mroCls, nativeMemberName);
                    if (!PGuards.isNullOrZero(result, lib)) {
                        return result;
                    }
                }
            }

            return readAttrNode.execute(PythonContext.get(readAttrNode).lookupType(PythonBuiltinClassType.PythonObject), managedMemberName);
        }

        @TruffleBoundary
        private static Object createSpecialHeapSlot(Object cls, HiddenKey managedMemberName, Node node) {
            Object func;
            if (managedMemberName == TypeBuiltins.TYPE_ALLOC || managedMemberName == TypeBuiltins.TYPE_DEL) {
                PythonObject object = PythonContext.get(null).lookupType(PythonBuiltinClassType.PythonObject);
                // We need to point to PyType_GenericAlloc or PyObject_GC_Del
                func = ReadAttributeFromObjectNode.getUncachedForceType().execute(object, managedMemberName);
                WriteAttributeToObjectNode.getUncached().execute(cls, managedMemberName, func);
            } else {
                // managedMemberName == TypeBuiltins.TYPE_DEALLOC
                // managedMemberName == TypeBuiltins.TYPE_CLEAR
                // managedMemberName == TypeBuiltins.TYPE_TRAVERSE
                throw PRaiseNode.raiseUncached(node, SystemError, tsLiteral("not supported yet!"));
            }
            return func;
        }

        @Specialization(guards = "isSpecialHeapSlot(cls, managedMemberName)")
        static Object doToAllocOrDelManaged(Object cls, @SuppressWarnings("unused") CFields nativeMemberName, HiddenKey managedMemberName,
                        @Shared @Cached("createForceType()") ReadAttributeFromObjectNode readAttrNode) {
            Object func = readAttrNode.execute(cls, managedMemberName);
            if (func == PNone.NO_VALUE) {
                func = createSpecialHeapSlot(cls, managedMemberName, readAttrNode);
            }
            return func;
        }
    }

    /**
     * Like {@link LookupNativeMemberInMRONode}, but for i64 values.
     */
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 48 -> 31
    public abstract static class LookupNativeI64MemberInMRONode extends Node {

        public final long execute(Object cls, CFields nativeMemberName, Object managedMemberName) {
            return execute(cls, nativeMemberName, managedMemberName, null);
        }

        public abstract long execute(Object cls, CFields nativeMemberName, Object managedMemberName, Function<PythonBuiltinClassType, Integer> builtinCallback);

        @Specialization
        static long doSingleContext(Object cls, CFields nativeMemberName, Object managedMemberName, Function<PythonBuiltinClassType, Integer> builtinCallback,
                        @Bind("this") Node inliningTarget,
                        @Cached GetMroStorageNode getMroNode,
                        @Cached SequenceStorageNodes.GetItemDynamicNode getItemNode,
                        @Cached("createForceType()") ReadAttributeFromObjectNode readAttrNode,
                        @Cached CStructAccess.ReadI64Node getTypeMemberNode,
                        @Cached PyNumberAsSizeNode asSizeNode) {
            CompilerAsserts.partialEvaluationConstant(builtinCallback);

            MroSequenceStorage mroStorage = getMroNode.execute(inliningTarget, cls);
            int n = mroStorage.length();

            for (int i = 0; i < n; i++) {
                PythonAbstractClass mroCls = (PythonAbstractClass) getItemNode.execute(inliningTarget, mroStorage, i);

                if (builtinCallback != null && mroCls instanceof PythonBuiltinClass builtinClass) {
                    return builtinCallback.apply(builtinClass.getType());
                } else if (PGuards.isManagedClass(mroCls)) {
                    Object attr = readAttrNode.execute(mroCls, managedMemberName);
                    if (attr != PNone.NO_VALUE) {
                        return asSizeNode.executeExact(null, inliningTarget, attr);
                    }
                } else {
                    assert PGuards.isNativeClass(mroCls) : "invalid class inheritance structure; expected native class";
                    return getTypeMemberNode.readFromObj((PythonNativeClass) mroCls, nativeMemberName);
                }
            }
            // return the value from PyBaseObject - assumed to be 0 for vectorcall_offset
            return nativeMemberName == CFields.PyTypeObject__tp_basicsize || nativeMemberName == CFields.PyTypeObject__tp_weaklistoffset ? CStructs.PyObject.size() : 0L;
        }
    }

    /**
     * This node is used for lookups of fields that are inherited from the dominant base instead of
     * MRO, such as {@code tp_basicsize}. For MRO lookup, use
     * {@link LookupNativeI64MemberInMRONode}.
     */
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 44 -> 26
    public abstract static class LookupNativeI64MemberFromBaseNode extends Node {

        public final long execute(Object cls, CFields nativeMemberName, Object managedMemberName) {
            return execute(cls, nativeMemberName, managedMemberName, null);
        }

        public abstract long execute(Object cls, CFields nativeMemberName, Object managedMemberName, Function<PythonBuiltinClassType, Integer> builtinCallback);

        @Specialization
        static long doSingleContext(Object cls, CFields nativeMember, Object managedMemberName, Function<PythonBuiltinClassType, Integer> builtinCallback,
                        @Bind("this") Node inliningTarget,
                        @Cached GetBaseClassNode getBaseClassNode,
                        @Cached("createForceType()") ReadAttributeFromObjectNode readAttrNode,
                        @Cached CStructAccess.ReadI64Node getTypeMemberNode,
                        @Cached PyNumberAsSizeNode asSizeNode) {
            CompilerAsserts.partialEvaluationConstant(builtinCallback);

            Object current = cls;
            do {
                if (current instanceof PythonBuiltinClassType pbct) {
                    current = PythonContext.get(inliningTarget).lookupType(pbct);
                }
                if (builtinCallback != null && current instanceof PythonBuiltinClass builtinClass) {
                    return builtinCallback.apply(builtinClass.getType());
                } else if (PGuards.isManagedClass(current)) {
                    Object attr = readAttrNode.execute(current, managedMemberName);
                    if (attr != PNone.NO_VALUE) {
                        return asSizeNode.executeExact(null, inliningTarget, attr);
                    }
                } else {
                    assert PGuards.isNativeClass(current) : "invalid class inheritance structure; expected native class";
                    return getTypeMemberNode.readFromObj((PythonNativeClass) current, nativeMember);
                }
                current = getBaseClassNode.execute(inliningTarget, current);
            } while (current != null);
            // return the value from PyBaseObject - assumed to be 0 for vectorcall_offset
            return nativeMember == CFields.PyTypeObject__tp_basicsize || nativeMember == CFields.PyTypeObject__tp_weaklistoffset ? CStructs.PyObject.size() : 0L;
        }
    }

    /**
     * Use this node to transform an exception to native if a Python exception was thrown during an
     * upcall and before returning to native code. This node will reify the exception appropriately
     * and register the exception as the current exception.
     */
    @GenerateInline(inlineByDefault = true)
    @GenerateCached
    @GenerateUncached
    public abstract static class TransformExceptionToNativeNode extends Node {

        public abstract void execute(Frame frame, Node inliningTarget, PException e);

        public final void execute(Node inliningTarget, PException e) {
            execute(null, inliningTarget, e);
        }

        public final void executeCached(PException e) {
            execute(null, this, e);
        }

        @Specialization
        static void setCurrentException(Frame frame, Node inliningTarget, PException e,
                        @Cached GetCurrentFrameRef getCurrentFrameRef,
                        @Cached GetThreadStateNode getThreadStateNode) {
            // TODO connect f_back
            getCurrentFrameRef.execute(frame, inliningTarget).markAsEscaped();
            getThreadStateNode.setCurrentException(inliningTarget, e);
        }
    }

    @GenerateUncached
    @GenerateCached
    @GenerateInline(false)
    public abstract static class PRaiseNativeNode extends Node {

        public final int raiseInt(Frame frame, int errorValue, PythonBuiltinClassType errType, TruffleString format, Object... arguments) {
            return executeInt(frame, errorValue, errType, format, arguments);
        }

        public final <T> T raise(Frame frame, T errorValue, PythonBuiltinClassType errType, TruffleString format, Object... arguments) {
            Object result = execute(frame, errorValue, errType, format, arguments);
            assert result == errorValue;
            return errorValue;
        }

        public final int raiseIntWithoutFrame(int errorValue, PythonBuiltinClassType errType, TruffleString format, Object... arguments) {
            return executeInt(null, errorValue, errType, format, arguments);
        }

        public abstract Object execute(Frame frame, Object errorValue, PythonBuiltinClassType errType, TruffleString format, Object[] arguments);

        public abstract int executeInt(Frame frame, int errorValue, PythonBuiltinClassType errType, TruffleString format, Object[] arguments);

        @Specialization
        static int doInt(Frame frame, int errorValue, PythonBuiltinClassType errType, TruffleString format, Object[] arguments,
                        @Bind("this") Node inliningTarget,
                        @Shared("raiseNode") @Cached PRaiseNode raiseNode,
                        @Shared("transformExceptionToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) {
            raiseNative(frame, inliningTarget, errType, format, arguments, raiseNode, transformExceptionToNativeNode);
            return errorValue;
        }

        @Specialization
        static Object doObject(Frame frame, Object errorValue, PythonBuiltinClassType errType, TruffleString format, Object[] arguments,
                        @Bind("this") Node inliningTarget,
                        @Shared("raiseNode") @Cached PRaiseNode raiseNode,
                        @Shared("transformExceptionToNativeNode") @Cached TransformExceptionToNativeNode transformExceptionToNativeNode) {
            raiseNative(frame, inliningTarget, errType, format, arguments, raiseNode, transformExceptionToNativeNode);
            return errorValue;
        }

        private static void raiseNative(Frame frame, Node inliningTarget, PythonBuiltinClassType errType, TruffleString format, Object[] arguments, PRaiseNode raiseNode,
                        TransformExceptionToNativeNode transformExceptionToNativeNode) {
            try {
                throw raiseNode.raise(errType, format, arguments);
            } catch (PException p) {
                transformExceptionToNativeNode.execute(frame, inliningTarget, p);
            }
        }

        @GenerateInline
        @GenerateUncached
        @GenerateCached(false)
        public abstract static class Lazy extends Node {

            public final PRaiseNativeNode get(Node inliningTarget) {
                return execute(inliningTarget);
            }

            abstract PRaiseNativeNode execute(Node inliningTarget);

            @Specialization
            static PRaiseNativeNode doIt(@Cached(inline = false) PRaiseNativeNode node) {
                return node;
            }
        }

    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    @ImportStatic(CApiGuards.class)
    public abstract static class AddRefCntNode extends PNodeWithContext {

        public abstract Object execute(Node inliningTarget, Object object, long value);

        @Specialization
        static Object doNativeWrapper(PythonNativeWrapper nativeWrapper, long value) {
            assert value >= 0 : "adding negative reference count; dealloc might not happen";
            CApiTransitions.incRef(nativeWrapper, value);
            return nativeWrapper;
        }

        @Specialization(guards = "!isNativeWrapper(object)", limit = "2")
        static Object doNativeObject(Object object, long value,
                        @Cached(inline = false) PCallCapiFunction callAddRefCntNode,
                        @CachedLibrary("object") InteropLibrary lib) {
            CApiContext cApiContext = PythonContext.get(callAddRefCntNode).getCApiContext();
            if (!lib.isNull(object) && cApiContext != null) {
                assert value >= 0 : "adding negative reference count; dealloc might not happen";
                cApiContext.checkAccess(object, lib);
                callAddRefCntNode.call(NativeCAPISymbol.FUN_ADDREF, object, value);
            }
            return object;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    @ImportStatic(CApiGuards.class)
    public abstract static class SubRefCntNode extends PNodeWithContext {
        private static final TruffleLogger LOGGER = CApiContext.getLogger(SubRefCntNode.class);

        public final long dec(Node inliningTarget, Object object) {
            return execute(inliningTarget, object, 1);
        }

        public abstract long execute(Node inliningTarget, Object object, long value);

        @Specialization
        static long doNativeWrapper(Node inliningTarget, PythonNativeWrapper nativeWrapper, long value,
                        @Cached FreeNode freeNode,
                        @Cached InlinedBranchProfile negativeProfile) {
            long refCount = CApiTransitions.decRef(nativeWrapper, value);
            if (refCount == 0) {
                // 'freeNode' acts as a branch profile
                freeNode.execute(inliningTarget, nativeWrapper);
            } else if (refCount < 0) {
                negativeProfile.enter(inliningTarget);
                LOGGER.severe(() -> "native wrapper has negative ref count: " + nativeWrapper);
            }
            return refCount;
        }

        @Specialization(guards = "!isNativeWrapper(object)", limit = "2")
        static long doNativeObject(Object object, long value,
                        @Cached(inline = false) PCallCapiFunction callAddRefCntNode,
                        @CachedLibrary("object") InteropLibrary lib) {
            CompilerDirectives.shouldNotReachHere("refcnt operation");
            PythonContext context = PythonContext.get(callAddRefCntNode);
            CApiContext cApiContext = context.getCApiContext();
            if (!lib.isNull(object) && cApiContext != null) {
                cApiContext.checkAccess(object, lib);
                long newRefcnt = (long) callAddRefCntNode.call(NativeCAPISymbol.FUN_SUBREF, object, value);
                if (context.getOption(PythonOptions.TraceNativeMemory) && newRefcnt < 0) {
                    LOGGER.severe(() -> "object has negative ref count: " + CApiContext.asHex(object));
                }
                return newRefcnt;
            }
            return 1;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    @ImportStatic(PGuards.class)
    public abstract static class ClearNativeWrapperNode extends Node {

        public abstract void execute(Node inliningTarget, Object delegate, PythonNativeWrapper nativeWrapper);

        @Specialization(guards = "!isPrimitiveNativeWrapper(nativeWrapper)")
        static void doPythonAbstractObject(PythonAbstractObject delegate, PythonNativeWrapper nativeWrapper) {
            // For non-temporary wrappers (all wrappers that need to preserve identity):
            // If this assertion fails, it indicates that the native code still uses a free'd native
            // wrapper.
            // TODO(fa): explicitly mark native wrappers to be identity preserving
            assert !(nativeWrapper instanceof PythonObjectNativeWrapper) || delegate.getNativeWrapper() == nativeWrapper : "inconsistent native wrappers";
            delegate.clearNativeWrapper();
        }

        @Specialization(guards = "delegate == null")
        static void doPrimitiveNativeWrapper(Node inliningTarget, @SuppressWarnings("unused") Object delegate, PrimitiveNativeWrapper nativeWrapper) {
            assert !isSmallIntegerWrapperSingleton(nativeWrapper, PythonContext.get(inliningTarget)) : "clearing primitive native wrapper singleton of small integer";
        }

        @Specialization(guards = "delegate != null")
        static void doPrimitiveNativeWrapperMaterialized(Node inliningTarget, PythonAbstractObject delegate, PrimitiveNativeWrapper nativeWrapper,
                        @Cached InlinedConditionProfile profile) {
            if (profile.profile(inliningTarget, delegate.getNativeWrapper() == nativeWrapper)) {
                assert !isSmallIntegerWrapperSingleton(nativeWrapper, PythonContext.get(inliningTarget)) : "clearing primitive native wrapper singleton of small integer";
                delegate.clearNativeWrapper();
            }
        }

        @Specialization(guards = {"delegate != null", "!isAnyPythonObject(delegate)"})
        static void doOther(@SuppressWarnings("unused") Object delegate, @SuppressWarnings("unused") PythonNativeWrapper nativeWrapper) {
            assert !isPrimitiveNativeWrapper(nativeWrapper);
            // ignore
        }

        static boolean isPrimitiveNativeWrapper(PythonNativeWrapper nativeWrapper) {
            return nativeWrapper instanceof PrimitiveNativeWrapper;
        }

        private static boolean isSmallIntegerWrapperSingleton(PrimitiveNativeWrapper nativeWrapper, PythonContext context) {
            return CApiGuards.isSmallIntegerWrapper(nativeWrapper) && GetNativeWrapperNode.doLongSmall(nativeWrapper.getLong(), context) == nativeWrapper;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class ResolveHandleNode extends PNodeWithContext {

        public abstract Object execute(Node inliningTarget, Object pointerObject);

        public static Object executeUncached(Object pointerObject) {
            return ResolveHandleNodeGen.getUncached().execute(null, pointerObject);
        }

        public abstract Object executeLong(Node inliningTarget, long pointer);

        @Specialization
        static Object resolveLongCached(long pointer) {
            Object lookup = CApiTransitions.lookupNative(pointer);
            if (lookup != null) {
                return lookup;
            }
            if (HandlePointerConverter.pointsToPyHandleSpace(pointer)) {
                return HandleResolver.resolve(pointer);
            }
            return pointer;
        }

        @Specialization(guards = "!isLong(pointerObject)")
        static Object resolveGeneric(Object pointerObject,
                        @CachedLibrary(limit = "3") InteropLibrary lib) {
            if (lib.isPointer(pointerObject)) {
                Object lookup;
                long pointer;
                try {
                    pointer = lib.asPointer(pointerObject);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere(e);
                }
                lookup = CApiTransitions.lookupNative(pointer);
                if (lookup != null) {
                    return lookup;
                }
                if (HandlePointerConverter.pointsToPyHandleSpace(pointer)) {
                    return HandleResolver.resolve(pointer);
                }
            }
            // In this case, it cannot be a handle so we can just return the pointer object. It
            // could, of course, still be a native pointer.
            return pointerObject;
        }
    }

    /**
     * Depending on the object's type, the size may need to be computed in very different ways. E.g.
     * any PyVarObject usually returns the number of contained elements.
     */
    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class ObSizeNode extends PNodeWithContext {

        public abstract long execute(Node inliningTarget, Object object);

        @Specialization
        static long doBoolean(boolean object) {
            return object ? 1 : 0;
        }

        @Specialization
        static long doInteger(int object) {
            return doLong(object);
        }

        @Specialization
        static long doLong(long object) {
            long t = PInt.abs(object);
            int sign = object < 0 ? -1 : 1;
            int size = 0;
            while (t != 0) {
                ++size;
                t >>>= PYLONG_BITS_IN_DIGIT.intValue();
            }
            return size * sign;
        }

        @Specialization
        static long doPInt(PInt object) {
            int bw = PYLONG_BITS_IN_DIGIT.intValue();
            int len = (PInt.bitLength(object.abs()) + bw - 1) / bw;
            return object.isNegative() ? -len : len;
        }

        @Specialization
        static long doPythonNativeVoidPtr(@SuppressWarnings("unused") PythonNativeVoidPtr object) {
            return ((Long.SIZE - 1) / PYLONG_BITS_IN_DIGIT.intValue() + 1);
        }

        @Specialization
        static long doClass(@SuppressWarnings("unused") PythonManagedClass object) {
            return 0; // dummy value
        }

        @Fallback
        static long doOther(Node inliningTarget, Object object,
                        @Cached PyObjectSizeNode sizeNode) {
            try {
                return sizeNode.execute(null, inliningTarget, object);
            } catch (PException e) {
                return -1;
            }
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class UnicodeFromFormatNode extends Node {
        private static Pattern pattern;

        private static Matcher match(String formatStr) {
            if (pattern == null) {
                pattern = Pattern.compile("%(?<flags>[-+ #0])?(?<width>\\d+)?(\\.(?<prec>\\d+))?(?<len>(l|ll|z))?(?<spec>[%cduixspAUVSR])");
            }
            return pattern.matcher(formatStr);
        }

        public abstract Object execute(Node inliningTarget, TruffleString format, Object vaList);

        @Specialization
        @TruffleBoundary
        Object doGeneric(TruffleString f, Object vaList) {
            // TODO use TruffleString [GR-38103]
            String format = f.toJavaStringUncached();

            // helper nodes
            NativeToPythonNode toJavaNode = NativeToPythonNodeGen.getUncached();
            CastToJavaStringNode castToJavaStringNode = CastToJavaStringNodeGen.getUncached();
            FromCharPointerNode fromCharPointerNode = FromCharPointerNodeGen.getUncached();
            InteropLibrary interopLibrary = InteropLibrary.getUncached();
            PRaiseNode raiseNode = PRaiseNode.getUncached();

            // set the encapsulating node reference to get a precise error position
            EncapsulatingNodeReference current = EncapsulatingNodeReference.getCurrent();
            current.set(this);
            StringBuilder result = new StringBuilder();
            int vaArgIdx = 0;
            Object unicodeObj;
            try {
                Matcher matcher = match(format);
                int cur = 0;
                while (matcher.find(cur)) {
                    // not all combinations are valid
                    boolean valid = false;

                    // add anything before the match
                    result.append(format, cur, matcher.start());

                    cur = matcher.end();

                    String spec = matcher.group("spec");
                    String len = matcher.group("len");
                    int prec = getPrec(matcher.group("prec"));
                    assert spec.length() == 1;
                    char la = spec.charAt(0);
                    PythonContext context = PythonContext.get(raiseNode);
                    switch (la) {
                        case '%':
                            // %%
                            result.append('%');
                            valid = true;
                            break;
                        case 'c':
                            int ordinal = getAndCastToInt(interopLibrary, raiseNode, vaList);
                            if (ordinal < 0 || ordinal > 0x110000) {
                                throw raiseNode.raise(PythonBuiltinClassType.OverflowError, ErrorMessages.CHARACTER_ARG_NOT_IN_RANGE);
                            }
                            result.append((char) ordinal);
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 'd':
                        case 'i':
                            // %d, %i, %ld, %li, %lld, %lli, %zd, %zi
                            if (len != null) {
                                switch (len) {
                                    case "ll":
                                    case "l":
                                    case "z":
                                        vaArgIdx++;
                                        result.append(castToLong(interopLibrary, raiseNode, GetNextVaArgNode.executeUncached(vaList)));
                                        valid = true;
                                        break;
                                }
                            } else {
                                result.append(getAndCastToInt(interopLibrary, raiseNode, vaList));
                                vaArgIdx++;
                                valid = true;
                            }
                            break;
                        case 'u':
                            // %u, %lu, %llu, %zu
                            if (len != null) {
                                switch (len) {
                                    case "ll":
                                    case "l":
                                    case "z":
                                        vaArgIdx++;
                                        result.append(castToLong(interopLibrary, raiseNode, GetNextVaArgNode.executeUncached(vaList)));
                                        valid = true;
                                        break;
                                }
                            } else {
                                result.append(Integer.toUnsignedString(getAndCastToInt(interopLibrary, raiseNode, vaList)));
                                vaArgIdx++;
                                valid = true;
                            }
                            break;
                        case 'x':
                            // %x
                            result.append(Integer.toHexString(getAndCastToInt(interopLibrary, raiseNode, vaList)));
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 's':
                            // %s
                            Object charPtr = GetNextVaArgNode.executeUncached(vaList);
                            String sValue;
                            if (interopLibrary.isNull(charPtr)) {
                                // CPython would segfault. Let's make debugging easier for ourselves
                                sValue = "(NULL)";
                            } else {
                                unicodeObj = fromCharPointerNode.execute(charPtr);
                                sValue = castToJavaStringNode.execute(unicodeObj);
                            }
                            try {
                                if (prec == -1) {
                                    result.append(sValue);
                                } else {
                                    result.append(sValue, 0, Math.min(sValue.length(), prec));
                                }
                            } catch (CannotCastException e) {
                                // That should really not happen because we created the unicode
                                // object with FromCharPointerNode which guarantees to return a
                                // String/PString.
                                throw CompilerDirectives.shouldNotReachHere();
                            }
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 'p':
                            // %p
                            Object ptr = GetNextVaArgNode.executeUncached(vaList);
                            long value;
                            if (interopLibrary.isPointer(ptr)) {
                                value = interopLibrary.asPointer(ptr);
                            } else if (interopLibrary.hasIdentity(ptr)) {
                                value = interopLibrary.identityHashCode(ptr);
                            } else {
                                value = System.identityHashCode(ptr);
                            }
                            result.append(PythonUtils.formatJString("0x%x", value));
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 'A':
                            // %A
                            result.append(callBuiltin(context, BuiltinNames.T_ASCII, getPyObject(vaList)));
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 'U':
                            // %U
                            result.append(castToJavaStringNode.execute(getPyObject(vaList)));
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 'V':
                            // %V
                            Object pyObjectPtr = GetNextVaArgNode.executeUncached(vaList);
                            if (InteropLibrary.getUncached().isNull(pyObjectPtr)) {
                                unicodeObj = fromCharPointerNode.execute(GetNextVaArgNode.executeUncached(vaList));
                            } else {
                                unicodeObj = toJavaNode.execute(pyObjectPtr);
                            }
                            result.append(castToJavaStringNode.execute(unicodeObj));
                            vaArgIdx += 2;
                            valid = true;
                            break;
                        case 'S':
                            // %S
                            result.append(callBuiltin(context, BuiltinNames.T_STR, getPyObject(vaList)));
                            vaArgIdx++;
                            valid = true;
                            break;
                        case 'R':
                            // %R
                            result.append(callBuiltin(context, BuiltinNames.T_REPR, getPyObject(vaList)));
                            vaArgIdx++;
                            valid = true;
                            break;
                    }
                    // this means, we did not detect a valid format specifier, so add the whole
                    // group
                    if (!valid) {
                        result.append(matcher.group());
                    }
                }
                // add anything after the last matched group (or the whole format string if nothing
                // matched)
                result.append(format, cur, format.length());
            } catch (InteropException e) {
                throw raiseNode.raise(PythonBuiltinClassType.SystemError, ErrorMessages.ERROR_WHEN_ACCESSING_VAR_ARG_AT_POS, vaArgIdx);
            } finally {
                current.get();
            }
            return toTruffleStringUncached(result.toString());
        }

        private static int getPrec(String prec) {
            if (prec == null) {
                return -1;
            }
            return Integer.parseInt(prec);
        }

        /**
         * Read an element from the {@code va_list} with the specified type and cast it to a Java
         * {@code int}. Throws a {@code SystemError} if this is not possible.
         */
        private static int getAndCastToInt(InteropLibrary lib, PRaiseNode raiseNode, Object vaList) throws InteropException {
            Object value = GetNextVaArgNode.executeUncached(vaList);
            if (lib.fitsInInt(value)) {
                try {
                    return lib.asInt(value);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
            if (!lib.isPointer(value)) {
                lib.toNative(value);
            }
            if (lib.isPointer(value)) {
                try {
                    return (int) lib.asPointer(value);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
            throw raiseNode.raise(PythonBuiltinClassType.SystemError, ErrorMessages.P_OBJ_CANT_BE_INTEPRETED_AS_INTEGER, value);
        }

        /**
         * Cast a value to a Java {@code long}. Throws a {@code SystemError} if this is not
         * possible.
         */
        private static long castToLong(InteropLibrary lib, PRaiseNode raiseNode, Object value) {
            if (lib.fitsInLong(value)) {
                try {
                    return lib.asLong(value);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
            if (!lib.isPointer(value)) {
                lib.toNative(value);
            }
            if (lib.isPointer(value)) {
                try {
                    return lib.asPointer(value);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            }
            throw raiseNode.raise(PythonBuiltinClassType.SystemError, ErrorMessages.P_OBJ_CANT_BE_INTEPRETED_AS_INTEGER, value);
        }

        private static Object getPyObject(Object vaList) throws InteropException {
            return NativeToPythonNode.executeUncached(GetNextVaArgNode.executeUncached(vaList));
        }

        @TruffleBoundary
        private static Object callBuiltin(PythonContext context, TruffleString builtinName, Object object) {
            Object attribute = PyObjectLookupAttr.executeUncached(context.getBuiltins(), builtinName);
            return CastToJavaStringNodeGen.getUncached().execute(CallNode.getUncached().execute(null, attribute, object));
        }
    }

    abstract static class MultiPhaseExtensionModuleInitNode extends Node {

        // according to definitions in 'moduleobject.h'
        static final int SLOT_PY_MOD_CREATE = 1;
        static final int SLOT_PY_MOD_EXEC = 2;

    }

    /**
     * Equivalent of {@code PyModule_FromDefAndSpec}. Creates a Python module from a module
     * definition structure:
     *
     * <pre>
     * typedef struct PyModuleDef {
     *     PyModuleDef_Base m_base;
     *     const char* m_name;
     *     const char* m_doc;
     *     Py_ssize_t m_size;
     *     PyMethodDef *m_methods;
     *     struct PyModuleDef_Slot* m_slots;
     *     traverseproc m_traverse;
     *     inquiry m_clear;
     *     freefunc m_free;
     * } PyModuleDef
     * </pre>
     */
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 68 -> 49
    public abstract static class CreateModuleNode extends MultiPhaseExtensionModuleInitNode {

        public abstract Object execute(CApiContext capiContext, ModuleSpec moduleSpec, Object moduleDef, Object library);

        @Specialization
        @TruffleBoundary
        static Object doGeneric(CApiContext capiContext, ModuleSpec moduleSpec, Object moduleDefWrapper, Object library,
                        @Bind("this") Node inliningTarget,
                        @Cached PythonObjectFactory factory,
                        @Cached InlinedConditionProfile errOccurredProfile,
                        @Cached CStructAccess.ReadPointerNode readPointer,
                        @Cached CStructAccess.ReadI64Node readI64,
                        @CachedLibrary(limit = "3") InteropLibrary interopLib,
                        @Cached FromCharPointerNode fromCharPointerNode,
                        @Cached WriteAttributeToObjectNode writeAttrNode,
                        @Cached WriteAttributeToDynamicObjectNode writeAttrToMethodNode,
                        @Cached CreateMethodNode addLegacyMethodNode,
                        @Cached NativeToPythonStealingNode toJavaNode,
                        @Cached CStructAccess.ReadPointerNode readPointerNode,
                        @Cached CStructAccess.ReadI32Node readI32Node,
                        @Cached PRaiseNode.Lazy raiseNode) {
            // call to type the pointer
            Object moduleDef = moduleDefWrapper instanceof PythonAbstractNativeObject ? ((PythonAbstractNativeObject) moduleDefWrapper).getPtr() : moduleDefWrapper;

            /*
             * The name of the module is taken from the module spec and *NOT* from the module
             * definition.
             */
            TruffleString mName = moduleSpec.name;
            Object mDoc;
            long mSize;
            // do not eagerly read the doc string; this turned out to be unnecessarily expensive
            Object docPtr = readPointer.read(moduleDef, PyModuleDef__m_doc);
            if (PGuards.isNullOrZero(docPtr, interopLib)) {
                mDoc = PNone.NO_VALUE;
            } else {
                mDoc = fromCharPointerNode.execute(docPtr);
            }

            mSize = readI64.read(moduleDef, PyModuleDef__m_size);

            if (mSize < 0) {
                throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.SystemError, ErrorMessages.M_SIZE_CANNOT_BE_NEGATIVE, mName);
            }

            // parse slot definitions
            Object createFunction = null;
            boolean hasExecutionSlots = false;
            Object slotDefinitions = readPointerNode.read(moduleDef, PyModuleDef__m_slots);
            if (!interopLib.isNull(slotDefinitions)) {
                loop: for (int i = 0;; i++) {
                    int slotId = readI32Node.readStructArrayElement(slotDefinitions, i, PyModuleDef_Slot__slot);
                    switch (slotId) {
                        case 0:
                            break loop;
                        case SLOT_PY_MOD_CREATE:
                            if (createFunction != null) {
                                throw raiseNode.get(inliningTarget).raise(SystemError, ErrorMessages.MODULE_HAS_MULTIPLE_CREATE_SLOTS, mName);
                            }
                            createFunction = readPointerNode.readStructArrayElement(slotDefinitions, i, PyModuleDef_Slot__value);
                            break;
                        case SLOT_PY_MOD_EXEC:
                            hasExecutionSlots = true;
                            break;
                        default:
                            throw raiseNode.get(inliningTarget).raise(SystemError, ErrorMessages.MODULE_USES_UNKNOW_SLOT_ID, mName, slotId);
                    }
                }
            }

            Object module;
            if (createFunction != null && !interopLib.isNull(createFunction)) {
                Object[] cArguments = new Object[]{PythonToNativeNode.executeUncached(moduleSpec.originalModuleSpec), moduleDef};
                try {
                    Object result;
                    if (!interopLib.isExecutable(createFunction)) {
                        Object signature = capiContext.getContext().getEnv().parseInternal(Source.newBuilder(J_NFI_LANGUAGE, "(POINTER,POINTER):POINTER", "exec").build()).call();
                        result = interopLib.execute(SignatureLibrary.getUncached().bind(signature, createFunction), cArguments);
                    } else {
                        result = interopLib.execute(createFunction, cArguments);
                    }
                    CheckFunctionResultNode.checkFunctionResult(inliningTarget, mName, interopLib.isNull(result), true, PythonLanguage.get(raiseNode), capiContext.getContext(), errOccurredProfile,
                                    ErrorMessages.CREATION_FAILD_WITHOUT_EXCEPTION, ErrorMessages.CREATION_RAISED_EXCEPTION);
                    module = toJavaNode.execute(result);
                } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere(e);
                }

                /*
                 * We are more strict than CPython and require this to be a PythonModule object.
                 * This means, if the custom 'create' function uses a native subtype of the module
                 * type, then we require it to call our new function.
                 */
                if (!(module instanceof PythonModule)) {
                    if (mSize > 0) {
                        throw raiseNode.get(inliningTarget).raise(SystemError, ErrorMessages.NOT_A_MODULE_OBJECT_BUT_REQUESTS_MODULE_STATE, mName);
                    }
                    if (hasExecutionSlots) {
                        throw raiseNode.get(inliningTarget).raise(SystemError, ErrorMessages.MODULE_SPECIFIES_EXEC_SLOTS_BUT_DIDNT_CREATE_INSTANCE, mName);
                    }
                    // otherwise CPython is just fine
                } else {
                    ((PythonModule) module).setNativeModuleDef(moduleDef);
                }
            } else {
                PythonModule pythonModule = factory.createPythonModule(mName);
                pythonModule.setNativeModuleDef(moduleDef);
                module = pythonModule;
            }

            Object methodDefinitions = readPointerNode.read(moduleDef, PyModuleDef__m_methods);
            if (!interopLib.isNull(methodDefinitions)) {
                for (int i = 0;; i++) {
                    PBuiltinFunction fun = addLegacyMethodNode.execute(inliningTarget, methodDefinitions, i);
                    if (fun == null) {
                        break;
                    }
                    PBuiltinMethod method = factory.createBuiltinMethod(module, fun);
                    writeAttrToMethodNode.execute(method, SpecialAttributeNames.T___MODULE__, mName);
                    writeAttrNode.execute(module, fun.getName(), method);
                }
            }

            writeAttrNode.execute(module, SpecialAttributeNames.T___DOC__, mDoc);
            writeAttrNode.execute(module, SpecialAttributeNames.T___LIBRARY__, library);
            capiContext.addLoadedExtensionLibrary(library);
            return module;
        }
    }

    /**
     * Equivalent of {@code PyModule_ExecDef}.
     */
    @GenerateUncached
    @GenerateInline(false) // footprint reduction 60 -> 42
    public abstract static class ExecModuleNode extends MultiPhaseExtensionModuleInitNode {

        public abstract int execute(CApiContext capiContext, PythonModule module, Object moduleDef);

        @Specialization
        @TruffleBoundary
        static int doGeneric(CApiContext capiContext, PythonModule module, Object moduleDef,
                        @Bind("this") Node inliningTarget,
                        @Cached ModuleGetNameNode getNameNode,
                        @Cached CStructAccess.ReadI64Node readI64,
                        @Cached CStructAccess.AllocateNode alloc,
                        @Cached CStructAccess.ReadPointerNode readPointerNode,
                        @Cached CStructAccess.ReadI32Node readI32Node,
                        @CachedLibrary(limit = "3") InteropLibrary interopLib,
                        @Cached PRaiseNode raiseNode) {
            InteropLibrary U = InteropLibrary.getUncached();
            // call to type the pointer

            TruffleString mName = getNameNode.execute(inliningTarget, module);
            long mSize = readI64.read(moduleDef, PyModuleDef__m_size);

            try {
                // allocate md_state if necessary
                if (mSize >= 0) {
                    /*
                     * TODO(fa): We currently leak 'md_state' and need to use a shared finalizer or
                     * similar. We ignore that for now since the size will usually be very small
                     * and/or we could also use a Truffle buffer object.
                     */
                    Object mdState = alloc.alloc(mSize == 0 ? 1 : mSize); // ensure non-null value
                    assert mdState != null && !InteropLibrary.getUncached().isNull(mdState);
                    module.setNativeModuleState(mdState);
                }

                // parse slot definitions
                Object slotDefinitions = readPointerNode.read(moduleDef, PyModuleDef__m_slots);
                if (interopLib.isNull(slotDefinitions)) {
                    return 0;
                }
                loop: for (int i = 0;; i++) {
                    int slotId = readI32Node.readStructArrayElement(slotDefinitions, i, PyModuleDef_Slot__slot);
                    switch (slotId) {
                        case 0:
                            break loop;
                        case SLOT_PY_MOD_CREATE:
                            // handled in CreateModuleNode
                            break;
                        case SLOT_PY_MOD_EXEC:
                            Object execFunction = readPointerNode.readStructArrayElement(slotDefinitions, i, PyModuleDef_Slot__value);
                            if (!U.isExecutable(execFunction)) {
                                Object signature = capiContext.getContext().getEnv().parseInternal(Source.newBuilder(J_NFI_LANGUAGE, "(POINTER):SINT32", "exec").build()).call();
                                execFunction = SignatureLibrary.getUncached().bind(signature, execFunction);
                            }
                            Object result = interopLib.execute(execFunction, PythonToNativeNode.executeUncached(module));
                            int iResult = interopLib.asInt(result);
                            /*
                             * It's a bit counterintuitive that we use 'isPrimitiveValue = false'
                             * but the function's return value is actually not a result but a status
                             * code. So, if the status code is '!=0' we know that an error occurred
                             * and won't ignore this if no error is set. This is then the same
                             * behaviour if we would have a pointer return type and got 'NULL'.
                             */
                            CheckFunctionResultNode.checkFunctionResult(inliningTarget, mName, iResult != 0, true, PythonLanguage.get(raiseNode), capiContext.getContext(),
                                            InlinedConditionProfile.getUncached(), ErrorMessages.EXECUTION_FAILED_WITHOUT_EXCEPTION, ErrorMessages.EXECUTION_RAISED_EXCEPTION);
                            break;
                        default:
                            throw raiseNode.raise(SystemError, ErrorMessages.MODULE_INITIALIZED_WITH_UNKNOWN_SLOT, mName, slotId);
                    }
                }
            } catch (UnsupportedMessageException | UnsupportedTypeException | ArityException e) {
                throw CompilerDirectives.shouldNotReachHere();
            }

            return 0;
        }
    }

    /**
     * <pre>
     *     struct PyMethodDef {
     *         const char * ml_name;
     *         PyCFunction  ml_meth;
     *         int          ml_flags;
     *         const char * ml_doc;
     *     };
     * </pre>
     */
    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class CreateMethodNode extends PNodeWithContext {

        public abstract PBuiltinFunction execute(Node inliningTarget, Object legacyMethodDef, int element);

        @Specialization
        static PBuiltinFunction doIt(Object methodDef, int element,
                        @CachedLibrary(limit = "2") InteropLibrary resultLib,
                        @Cached(inline = false) CStructAccess.ReadPointerNode readPointerNode,
                        @Cached(inline = false) CStructAccess.ReadI32Node readI32Node,
                        @Cached(inline = false) FromCharPointerNode fromCharPointerNode,
                        @Cached(inline = false) PythonObjectFactory factory,
                        @Cached(inline = false) WriteAttributeToDynamicObjectNode writeAttributeToDynamicObjectNode) {
            Object methodNamePtr = readPointerNode.readStructArrayElement(methodDef, element, PyMethodDef__ml_name);
            if (resultLib.isNull(methodNamePtr) || (methodNamePtr instanceof Long && ((long) methodNamePtr) == 0)) {
                return null;
            }
            TruffleString methodName = fromCharPointerNode.execute(methodNamePtr);
            // note: 'ml_doc' may be NULL; in this case, we would store 'None'
            Object methodDoc = PNone.NONE;
            Object methodDocPtr = readPointerNode.readStructArrayElement(methodDef, element, PyMethodDef__ml_doc);
            if (!resultLib.isNull(methodDocPtr)) {
                methodDoc = fromCharPointerNode.execute(methodDocPtr, false);
            }

            int flags = readI32Node.readStructArrayElement(methodDef, element, PyMethodDef__ml_flags);
            Object mlMethObj = readPointerNode.readStructArrayElement(methodDef, element, PyMethodDef__ml_meth);
            // CPy-style methods
            // TODO(fa) support static and class methods
            PExternalFunctionWrapper sig = PExternalFunctionWrapper.fromMethodFlags(flags);
            RootCallTarget callTarget = PExternalFunctionWrapper.getOrCreateCallTarget(sig, PythonLanguage.get(factory), methodName, true, CExtContext.isMethStatic(flags));
            mlMethObj = NativeCExtSymbol.ensureExecutable(mlMethObj, sig);
            PKeyword[] kwDefaults = ExternalFunctionNodes.createKwDefaults(mlMethObj);
            PBuiltinFunction function = factory.createBuiltinFunction(methodName, null, PythonUtils.EMPTY_OBJECT_ARRAY, kwDefaults, flags, callTarget);

            // write doc string; we need to directly write to the storage otherwise it is disallowed
            // writing to builtin types.
            writeAttributeToDynamicObjectNode.execute(function.getStorage(), SpecialAttributeNames.T___DOC__, methodDoc);

            return function;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class HasNativeBufferNode extends PNodeWithContext {
        public abstract boolean execute(Node inliningTarget, PythonAbstractNativeObject object);

        @Specialization
        static boolean readTpAsBuffer(PythonAbstractNativeObject object,
                        @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Cached(inline = false) CStructAccess.ReadPointerNode readType,
                        @Cached(inline = false) CStructAccess.ReadPointerNode readAsBuffer) {
            Object type = readType.readFromObj(object, PyObject__ob_type);
            Object result = readAsBuffer.read(type, PyTypeObject__tp_as_buffer);
            return !PGuards.isNullOrZero(result, lib);
        }
    }

    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class CreateMemoryViewFromNativeNode extends PNodeWithContext {
        public abstract PMemoryView execute(Node inliningTarget, PythonNativeObject object, int flags);

        @Specialization
        static PMemoryView fromNative(PythonNativeObject buf, int flags,
                        @Cached(inline = false) PythonToNativeNode toSulongNode,
                        @Cached(inline = false) NativeToPythonNode asPythonObjectNode,
                        @Cached(inline = false) PCallCapiFunction callCapiFunction,
                        @Cached(inline = false) DefaultCheckFunctionResultNode checkFunctionResultNode) {
            Object result = callCapiFunction.call(FUN_PY_TRUFFLE_MEMORYVIEW_FROM_OBJECT, toSulongNode.execute(buf), flags);
            checkFunctionResultNode.execute(PythonContext.get(callCapiFunction), FUN_PY_TRUFFLE_MEMORYVIEW_FROM_OBJECT.getTsName(), result);
            return (PMemoryView) asPythonObjectNode.execute(result);
        }
    }

    /**
     * Decrements the ref count by one of any {@link PythonNativeWrapper} object.
     * <p>
     * This node avoids memory leaks for arguments given to native.<br>
     * Problem description:<br>
     * {@link PythonNativeWrapper} objects given to C code may go to native, i.e., a handle will be
     * allocated. In this case, no ref count manipulation is done since the C code considers the
     * reference to be borrowed and the Python code just doesn't do it because we have a GC. This
     * means that the handle will stay allocated and we are leaking the wrapper object.
     * </p>
     */
    @GenerateInline(false)
    @ImportStatic(CApiGuards.class)
    abstract static class ReleaseNativeWrapperNode extends Node {

        public abstract void execute(Object pythonObject);

        @Specialization
        static void doNativeWrapper(PythonNativeWrapper nativeWrapper,
                        @Bind("this") Node inliningTarget,
                        @Cached TraverseNativeWrapperNode traverseNativeWrapperNode,
                        @Cached SubRefCntNode subRefCntNode) {
            // in the cached case, refCntNode acts as a branch profile
            // if (subRefCntNode.dec(nativeWrapper) == 0) {
            // traverseNativeWrapperNode.execute(inliningTarget, nativeWrapper.getDelegate());
            // }
        }

        @Specialization(guards = "!isNativeWrapper(object)")
        @SuppressWarnings("unused")
        static void doOther(Object object) {
            // just do nothing; this is an implicit profile
        }
    }

    /**
     * Traverses the items of a tuple and applies {@link ReleaseNativeWrapperNode} on the items if
     * the tuple is up to be released.
     */
    @GenerateInline
    @GenerateCached(false)
    abstract static class TraverseNativeWrapperNode extends Node {

        public abstract void execute(Node inliningTarget, Object containerObject);

        @Specialization
        static void doTuple(Node inliningTarget, PTuple tuple,
                        @Cached ToArrayNode toArrayNode,
                        @Cached SubRefCntNode subRefCntNode) {

            Object[] values = toArrayNode.execute(inliningTarget, tuple.getSequenceStorage());
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof PythonObject) {
                    PythonNativeWrapper nativeWrapper = ((PythonObject) value).getNativeWrapper();
                    // only traverse if refCnt != 0; this will break the cycle
                    if (nativeWrapper != null) {
                        subRefCntNode.dec(inliningTarget, nativeWrapper);
                    }
                }
            }
        }

        @Fallback
        static void doOther(@SuppressWarnings("unused") Object other) {
            // do nothing
        }
    }

    @GenerateUncached
    @GenerateInline
    @GenerateCached(false)
    @ImportStatic({CApiGuards.class, PGuards.class})
    public abstract static class CreateFunctionNode extends Node {

        private static final TruffleLogger LOGGER = CApiContext.getLogger(CreateFunctionNode.class);

        public static Object executeUncached(TruffleString name, Object callable, Object wrapper, Object type, Object flags) {
            return CreateFunctionNodeGen.getUncached().execute(null, name, callable, wrapper, type, flags);
        }

        public abstract Object execute(Node inliningTarget, TruffleString name, Object callable, Object wrapper, Object type, Object flags);

        @Specialization(guards = {"!isNoValue(type)", "isNoValue(wrapper)"})
        static Object doPythonCallableWithoutWrapper(@SuppressWarnings("unused") TruffleString name, PythonNativeWrapper callable, @SuppressWarnings("unused") PNone wrapper,
                        @SuppressWarnings("unused") Object type, @SuppressWarnings("unused") Object flags) {
            // This can happen if a native type inherits slots from a managed type. Therefore,
            // something like 'base->tp_new' will be a wrapper of the managed '__new__'. So, in this
            // case, we assume that the object is already callable.
            return callable.getDelegate();
        }

        @Specialization(guards = "!isNoValue(type)")
        @TruffleBoundary
        static Object doPythonCallable(TruffleString name, PythonNativeWrapper callable, int signature, Object type, int flags) {
            // This can happen if a native type inherits slots from a managed type. Therefore,
            // something like 'base->tp_new' will be a wrapper of the managed '__new__'. So, in this
            // case, we assume that the object is already callable.
            Object managedCallable = callable.getDelegate();
            PythonContext context = PythonContext.get(null);
            PythonLanguage language = context.getLanguage();
            PBuiltinFunction function = PExternalFunctionWrapper.createWrapperFunction(name, managedCallable, type, flags, signature, language, context.factory(), false);
            return function != null ? function : managedCallable;
        }

        @Specialization(guards = {"!isNativeWrapper(callable)"})
        @TruffleBoundary
        static Object doNativeCallableWithWrapper(TruffleString name, Object callable, int signature, Object type, int flags,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib) {
            /*
             * This can happen if a native type inherits slots from a managed type. For example, if
             * a native type inherits 'base->tp_richcompare' and this is '__truffle_richcompare__'
             * and we are going to install it as '__eq__', we still need to have a wrapper around
             * the managed callable since we need to bind the 3rd argument.
             */
            PythonContext context = PythonContext.get(null);
            Object resolvedCallable = resolveClosurePointer(context, callable, lib);
            boolean doArgAndResultConversion;
            if (resolvedCallable != null) {
                doArgAndResultConversion = false;
            } else {
                doArgAndResultConversion = true;
                resolvedCallable = callable;
            }
            PythonLanguage language = context.getLanguage();
            PBuiltinFunction function = PExternalFunctionWrapper.createWrapperFunction(name, resolvedCallable, type, flags, signature, language, context.factory(), doArgAndResultConversion);
            return function != null ? function : resolvedCallable;
        }

        @Specialization(guards = {"isNoValue(wrapper)", "!isNativeWrapper(callable)"})
        @TruffleBoundary
        static PBuiltinFunction doNativeCallableWithoutWrapper(TruffleString name, Object callable, Object type, @SuppressWarnings("unused") PNone wrapper, @SuppressWarnings("unused") Object flags,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib) {
            /*
             * This can happen if a native type inherits slots from a managed type. Therefore,
             * something like 'base->tp_new' will be a wrapper of the managed '__new__'. In this
             * case, we can just return the managed callable since we do also not have a wrapper
             * that could shuffle or bind arguments.
             */
            PythonContext context = PythonContext.get(null);
            PBuiltinFunction managedCallable = resolveClosurePointer(context, callable, lib);
            if (managedCallable != null) {
                return managedCallable;
            }
            PythonLanguage language = context.getLanguage();
            return PExternalFunctionWrapper.createWrapperFunction(name, callable, type, 0, PExternalFunctionWrapper.DIRECT, language, context.factory(), true);
        }

        @TruffleBoundary
        public static PBuiltinFunction resolveClosurePointer(PythonContext context, Object callable, InteropLibrary lib) {
            if (lib.isPointer(callable)) {
                long pointer;
                try {
                    pointer = lib.asPointer(callable);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere(e);
                }
                Object delegate = context.getCApiContext().getClosureDelegate(pointer);
                if (delegate instanceof PBuiltinFunction function) {
                    LOGGER.fine(() -> PythonUtils.formatJString("forwarding %d 0x%x to %s", pointer, pointer, function));
                    return function;
                }
            }
            return null;
        }
    }
}
