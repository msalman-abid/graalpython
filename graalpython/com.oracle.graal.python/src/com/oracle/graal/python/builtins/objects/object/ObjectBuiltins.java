/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates.
 * Copyright (c) 2014, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.oracle.graal.python.builtins.objects.object;

import static com.oracle.graal.python.nodes.PGuards.isDeleteMarker;
import static com.oracle.graal.python.nodes.PGuards.isDict;
import static com.oracle.graal.python.nodes.PGuards.isNoValue;
import static com.oracle.graal.python.nodes.PGuards.isPythonModule;
import static com.oracle.graal.python.nodes.PGuards.isPythonObject;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.J___CLASS__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.J___DICT__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___CLASS__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___DICT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J_RICHCMP;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___DELATTR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___DIR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___EQ__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___FORMAT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___GETATTRIBUTE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___GE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___GT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___HASH__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___INIT_SUBCLASS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___INIT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___LE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___LT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___NE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___REDUCE_EX__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___REDUCE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___REPR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___SETATTR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___SIZEOF__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___STR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___SUBCLASSHOOK__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.T_UPDATE;
import static com.oracle.graal.python.nodes.SpecialMethodNames.T___LEN__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.T___NE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.T___REDUCE__;
import static com.oracle.graal.python.nodes.StringLiterals.T_NONE;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.AttributeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;

import java.util.List;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.annotations.ArgumentClinic;
import com.oracle.graal.python.annotations.ArgumentClinic.ClinicConversion;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.modules.BuiltinConstructorsFactory;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.cext.PythonAbstractNativeObject;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.function.BuiltinMethodDescriptor;
import com.oracle.graal.python.builtins.objects.function.PBuiltinFunction;
import com.oracle.graal.python.builtins.objects.function.PKeyword;
import com.oracle.graal.python.builtins.objects.getsetdescriptor.DescriptorBuiltins.DescrDeleteNode;
import com.oracle.graal.python.builtins.objects.getsetdescriptor.DescriptorBuiltins.DescrGetNode;
import com.oracle.graal.python.builtins.objects.getsetdescriptor.DescriptorBuiltins.DescrSetNode;
import com.oracle.graal.python.builtins.objects.getsetdescriptor.DescriptorDeleteMarker;
import com.oracle.graal.python.builtins.objects.method.PBuiltinMethod;
import com.oracle.graal.python.builtins.objects.object.ObjectBuiltinsClinicProviders.FormatNodeClinicProviderGen;
import com.oracle.graal.python.builtins.objects.object.ObjectBuiltinsClinicProviders.ReduceExNodeClinicProviderGen;
import com.oracle.graal.python.builtins.objects.object.ObjectBuiltinsFactory.GetAttributeNodeFactory;
import com.oracle.graal.python.builtins.objects.set.PSet;
import com.oracle.graal.python.builtins.objects.type.PythonBuiltinClass;
import com.oracle.graal.python.builtins.objects.type.SpecialMethodSlot;
import com.oracle.graal.python.builtins.objects.type.TypeNodes;
import com.oracle.graal.python.builtins.objects.type.TypeNodes.CheckCompatibleForAssigmentNode;
import com.oracle.graal.python.builtins.objects.type.TypeNodes.GetBaseClassNode;
import com.oracle.graal.python.lib.PyObjectLookupAttr;
import com.oracle.graal.python.lib.PyObjectSizeNode;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.attributes.LookupAttributeInMRONode;
import com.oracle.graal.python.nodes.attributes.LookupCallableSlotInMRONode;
import com.oracle.graal.python.nodes.attributes.ReadAttributeFromObjectNode;
import com.oracle.graal.python.nodes.attributes.WriteAttributeToObjectNode;
import com.oracle.graal.python.nodes.call.CallNode;
import com.oracle.graal.python.nodes.call.special.CallBinaryMethodNode;
import com.oracle.graal.python.nodes.call.special.CallTernaryMethodNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallBinaryNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallUnaryNode;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.expression.BinaryComparisonNode;
import com.oracle.graal.python.nodes.expression.BinaryComparisonNodeFactory;
import com.oracle.graal.python.nodes.expression.CoerceToBooleanNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonTernaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonVarargsBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.clinic.ArgumentClinicProvider;
import com.oracle.graal.python.nodes.object.BuiltinClassProfiles.InlineIsBuiltinClassProfile;
import com.oracle.graal.python.nodes.object.BuiltinClassProfiles.IsOtherBuiltinClassProfile;
import com.oracle.graal.python.nodes.object.DeleteDictNode;
import com.oracle.graal.python.nodes.object.GetClassNode;
import com.oracle.graal.python.nodes.object.GetOrCreateDictNode;
import com.oracle.graal.python.nodes.object.IsNode;
import com.oracle.graal.python.nodes.object.SetDictNode;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToTruffleStringNode;
import com.oracle.graal.python.nodes.util.SplitArgsNode;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.api.strings.TruffleString;

@CoreFunctions(extendClasses = PythonBuiltinClassType.PythonObject)
public final class ObjectBuiltins extends PythonBuiltins {

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return ObjectBuiltinsFactory.getFactories();
    }

    @Builtin(name = J___CLASS__, minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2, isGetter = true, isSetter = true)
    @GenerateNodeFactory
    abstract static class ClassNode extends PythonBinaryBuiltinNode {

        @Specialization(guards = "isNoValue(value)")
        static Object getClass(Object self, @SuppressWarnings("unused") PNone value,
                        @Bind("this") Node inliningTarget,
                        @Exclusive @Cached GetClassNode getClassNode) {
            return getClassNode.execute(inliningTarget, self);
        }

        @Specialization(guards = "isNativeClass(klass)")
        static Object setClass(@SuppressWarnings("unused") Object self, @SuppressWarnings("unused") Object klass,
                        @Shared @Cached PRaiseNode raiseNode) {
            throw raiseNode.raise(TypeError, ErrorMessages.CLASS_ASSIGNMENT_ONLY_SUPPORTED_FOR_HEAP_TYPES_OR_MODTYPE_SUBCLASSES);
        }

        @Specialization(guards = "isPythonClass(value) || isPythonBuiltinClassType(value)")
        static PNone setClass(VirtualFrame frame, PythonObject self, Object value,
                        @Bind("this") Node inliningTarget,
                        @CachedLibrary(limit = "4") DynamicObjectLibrary dylib,
                        @Cached IsOtherBuiltinClassProfile classProfile1,
                        @Cached IsOtherBuiltinClassProfile classProfile2,
                        @Cached CheckCompatibleForAssigmentNode checkCompatibleForAssigmentNode,
                        @Exclusive @Cached GetClassNode getClassNode,
                        @Exclusive @Cached PRaiseNode.Lazy raiseNode) {
            Object type = getClassNode.execute(inliningTarget, self);
            if (isBuiltinClassNotModule(inliningTarget, value, classProfile1) || PGuards.isNativeClass(value) || isBuiltinClassNotModule(inliningTarget, type, classProfile2) ||
                            PGuards.isNativeClass(type)) {
                throw raiseNode.get(inliningTarget).raise(TypeError, ErrorMessages.CLASS_ASSIGNMENT_ONLY_SUPPORTED_FOR_HEAP_TYPES_OR_MODTYPE_SUBCLASSES);
            }

            checkCompatibleForAssigmentNode.execute(frame, type, value);
            self.setPythonClass(value, dylib);
            return PNone.NONE;
        }

        private static boolean isBuiltinClassNotModule(Node inliningTarget, Object type, IsOtherBuiltinClassProfile classProfile) {
            return classProfile.profileIsOtherBuiltinClass(inliningTarget, type, PythonBuiltinClassType.PythonModule);
        }

        @Specialization(guards = {"isPythonClass(value) || isPythonBuiltinClassType(value)", "!isPythonObject(self)"})
        static Object getClass(@SuppressWarnings("unused") Object self, @SuppressWarnings("unused") Object value,
                        @Shared @Cached PRaiseNode raiseNode) {
            throw raiseNode.raise(TypeError, ErrorMessages.CLASS_ASSIGNMENT_ONLY_SUPPORTED_FOR_HEAP_TYPES_OR_MODTYPE_SUBCLASSES);
        }

        @Fallback
        static Object getClassError(@SuppressWarnings("unused") Object self, Object value,
                        @Shared @Cached PRaiseNode raiseNode) {
            throw raiseNode.raise(TypeError, ErrorMessages.CLASS_MUST_BE_SET_TO_CLASS, value);
        }
    }

    @Builtin(name = J___INIT__, takesVarArgs = true, minNumOfPositionalArgs = 1, takesVarKeywordArgs = true)
    @GenerateNodeFactory
    @ImportStatic(SpecialMethodSlot.class)
    public abstract static class InitNode extends PythonVarargsBuiltinNode {
        @Child private SplitArgsNode splitArgsNode;

        @Override
        public final Object varArgExecute(VirtualFrame frame, @SuppressWarnings("unused") Object self, Object[] arguments, PKeyword[] keywords) throws VarargsBuiltinDirectInvocationNotSupported {
            if (splitArgsNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                splitArgsNode = insert(SplitArgsNode.create());
            }
            return execute(frame, arguments[0], splitArgsNode.executeCached(arguments), keywords);
        }

        @Specialization(guards = {"arguments.length == 0", "keywords.length == 0"})
        @SuppressWarnings("unused")
        static PNone initNoArgs(Object self, Object[] arguments, PKeyword[] keywords) {
            return PNone.NONE;
        }

        @Specialization(replaces = "initNoArgs")
        @SuppressWarnings({"unused", "truffle-static-method"})
        PNone init(Object self, Object[] arguments, PKeyword[] keywords,
                        @Bind("this") Node inliningTarget,
                        @Cached GetClassNode getClassNode,
                        @Cached InlinedConditionProfile overridesNew,
                        @Cached InlinedConditionProfile overridesInit,
                        @Cached("create(Init)") LookupCallableSlotInMRONode lookupInit,
                        @Cached(value = "createLookupProfile(getClassNode)", inline = false) ValueProfile profileInit,
                        @Cached(value = "createClassProfile()", inline = false) ValueProfile profileInitFactory,
                        @Cached("create(New)") LookupCallableSlotInMRONode lookupNew,
                        @Cached(value = "createLookupProfile(getClassNode)", inline = false) ValueProfile profileNew,
                        @Cached(value = "createClassProfile()", inline = false) ValueProfile profileNewFactory) {
            if (arguments.length != 0 || keywords.length != 0) {
                Object type = getClassNode.execute(inliningTarget, self);
                if (overridesNew.profile(inliningTarget, overridesBuiltinMethod(type, profileInit, lookupInit, profileInitFactory, ObjectBuiltinsFactory.InitNodeFactory.class))) {
                    throw raise(TypeError, ErrorMessages.INIT_TAKES_ONE_ARG_OBJECT);
                }

                if (overridesInit.profile(inliningTarget, !overridesBuiltinMethod(type, profileNew, lookupNew, profileNewFactory, BuiltinConstructorsFactory.ObjectNodeFactory.class))) {
                    throw raise(TypeError, ErrorMessages.INIT_TAKES_ONE_ARG, type);
                }
            }
            return PNone.NONE;
        }

        protected static ValueProfile createLookupProfile(Node node) {
            if (PythonLanguage.get(node).isSingleContext()) {
                return ValueProfile.createIdentityProfile();
            } else {
                return ValueProfile.createClassProfile();
            }
        }

        /**
         * Simple utility method to check if a method was overridden. The {@code profile} parameter
         * must {@emph not} be an identity profile when AST sharing is enabled.
         */
        public static <T extends NodeFactory<? extends PythonBuiltinBaseNode>> boolean overridesBuiltinMethod(Object type, ValueProfile profile, LookupCallableSlotInMRONode lookup,
                        ValueProfile factoryProfile, Class<T> builtinNodeFactoryClass) {
            Object method = profile.profile(lookup.execute(type));
            if (method instanceof PBuiltinFunction) {
                NodeFactory<? extends PythonBuiltinBaseNode> factory = factoryProfile.profile(((PBuiltinFunction) method).getBuiltinNodeFactory());
                return !builtinNodeFactoryClass.isInstance(factory);
            } else if (method instanceof PBuiltinMethod) {
                NodeFactory<? extends PythonBuiltinBaseNode> factory = factoryProfile.profile(((PBuiltinMethod) method).getBuiltinFunction().getBuiltinNodeFactory());
                return !builtinNodeFactoryClass.isInstance(factory);
            } else if (method instanceof BuiltinMethodDescriptor) {
                return !((BuiltinMethodDescriptor) method).isSameFactory(builtinNodeFactoryClass);
            }
            return true;
        }
    }

    @Builtin(name = J___HASH__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class HashNode extends PythonUnaryBuiltinNode {
        @Specialization
        public int hash(PythonBuiltinClassType self) {
            return hash(getContext().lookupType(self));
        }

        @TruffleBoundary
        @Specialization(guards = "!isPythonBuiltinClassType(self)")
        public static int hash(Object self) {
            return self.hashCode();
        }
    }

    @Builtin(name = J___EQ__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class EqNode extends PythonBinaryBuiltinNode {
        @Specialization
        static Object eq(Object self, Object other,
                        @Bind("this") Node inliningTarget,
                        @Cached InlinedConditionProfile isEq,
                        @Cached IsNode isNode) {
            if (isEq.profile(inliningTarget, isNode.execute(self, other))) {
                return true;
            } else {
                // Return NotImplemented instead of False, so if two objects are compared, both get
                // a chance at the comparison
                return PNotImplemented.NOT_IMPLEMENTED;
            }
        }
    }

    @Builtin(name = J___NE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class NeNode extends PythonBinaryBuiltinNode {

        @Child private LookupAndCallBinaryNode eqNode;
        @Child private CoerceToBooleanNode ifFalseNode;

        @Specialization
        static boolean ne(PythonAbstractNativeObject self, PythonAbstractNativeObject other,
                        @Cached CExtNodes.PointerCompareNode nativeNeNode) {
            return nativeNeNode.execute(T___NE__, self, other);
        }

        @Fallback
        Object ne(VirtualFrame frame, Object self, Object other) {
            if (eqNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                eqNode = insert(LookupAndCallBinaryNode.create(SpecialMethodSlot.Eq));
            }
            Object result = eqNode.executeObject(frame, self, other);
            if (result == PNotImplemented.NOT_IMPLEMENTED) {
                return result;
            }
            if (ifFalseNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                ifFalseNode = insert(CoerceToBooleanNode.createIfFalseNode());
            }
            return ifFalseNode.executeBooleanCached(frame, result);
        }
    }

    @Builtin(name = J___LT__, minNumOfPositionalArgs = 2)
    @Builtin(name = J___LE__, minNumOfPositionalArgs = 2)
    @Builtin(name = J___GT__, minNumOfPositionalArgs = 2)
    @Builtin(name = J___GE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LtLeGtGeNode extends PythonBinaryBuiltinNode {
        @Specialization
        @SuppressWarnings("unused")
        static Object notImplemented(Object self, Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }
    }

    @Builtin(name = J___STR__, minNumOfPositionalArgs = 1, doc = "Return str(self).")
    @GenerateNodeFactory
    abstract static class StrNode extends PythonUnaryBuiltinNode {
        @Specialization
        static Object str(VirtualFrame frame, Object self,
                        @Cached("create(Repr)") LookupAndCallUnaryNode reprNode) {
            return reprNode.executeObject(frame, self);
        }
    }

    @Builtin(name = J___REPR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class ReprNode extends PythonUnaryBuiltinNode {

        @Specialization(guards = "isNone(self)")
        static TruffleString reprNone(@SuppressWarnings("unused") PNone self) {
            return T_NONE;
        }

        @Specialization(guards = "!isNone(self)")
        static TruffleString repr(VirtualFrame frame, Object self,
                        @Bind("this") Node inliningTarget,
                        @Cached ObjectNodes.DefaultObjectReprNode defaultReprNode) {
            return defaultReprNode.execute(frame, inliningTarget, self);
        }
    }

    @ImportStatic(PGuards.class)
    @Builtin(name = J___GETATTRIBUTE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GetAttributeNode extends PythonBinaryBuiltinNode {
        @CompilationFinal private int profileFlags = 0;
        private static final int HAS_DESCR = 1;
        private static final int HAS_DATA_DESCR = 2;
        private static final int HAS_VALUE = 4;
        private static final int HAS_NO_VALUE = 8;

        @Child private LookupCallableSlotInMRONode lookupGetNode;
        @Child private LookupCallableSlotInMRONode lookupSetNode;
        @Child private LookupCallableSlotInMRONode lookupDeleteNode;
        @Child private CallTernaryMethodNode dispatchGet;
        @Child private ReadAttributeFromObjectNode attrRead;
        @Child private GetClassNode getDescClassNode;

        @Idempotent
        protected static int tsLen(TruffleString ts) {
            CompilerAsserts.neverPartOfCompilation();
            return TruffleString.CodePointLengthNode.getUncached().execute(ts, TS_ENCODING) + 1;
        }

        // Shortcut, only useful for interpreter performance, but doesn't hurt peak
        @Specialization(guards = {"keyObj == cachedKey", "tsLen(cachedKey) < 32"}, limit = "1")
        @SuppressWarnings("truffle-static-method")
        Object doItTruffleString(VirtualFrame frame, Object object, @SuppressWarnings("unused") TruffleString keyObj,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached("keyObj") TruffleString cachedKey,
                        @Exclusive @Cached GetClassNode getClassNode,
                        @Cached("create(cachedKey)") LookupAttributeInMRONode lookup,
                        @Exclusive @Cached PythonObjectFactory.Lazy factory,
                        @Exclusive @Cached PRaiseNode.Lazy raiseNode) {
            Object type = getClassNode.execute(inliningTarget, object);
            Object descr = lookup.execute(type);
            return fullLookup(frame, inliningTarget, object, cachedKey, type, descr, factory, raiseNode);
        }

        @Specialization
        @SuppressWarnings("truffle-static-method")
        Object doIt(VirtualFrame frame, Object object, Object keyObj,
                        @Bind("this") Node inliningTarget,
                        @Cached LookupAttributeInMRONode.Dynamic lookup,
                        @Exclusive @Cached GetClassNode getClassNode,
                        @Cached CastToTruffleStringNode castKeyToStringNode,
                        @Exclusive @Cached PythonObjectFactory.Lazy factory,
                        @Exclusive @Cached PRaiseNode.Lazy raiseNode) {
            TruffleString key;
            try {
                key = castKeyToStringNode.execute(inliningTarget, keyObj);
            } catch (CannotCastException e) {
                throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.ATTR_NAME_MUST_BE_STRING, keyObj);
            }

            Object type = getClassNode.execute(inliningTarget, object);
            Object descr = lookup.execute(type, key);
            return fullLookup(frame, inliningTarget, object, key, type, descr, factory, raiseNode);
        }

        private Object fullLookup(VirtualFrame frame, Node inliningTarget, Object object, TruffleString key, Object type, Object descr, PythonObjectFactory.Lazy factory, PRaiseNode.Lazy raiseNode) {
            Object dataDescClass = null;
            boolean hasDescr = descr != PNone.NO_VALUE;
            if (hasDescr && (profileFlags & HAS_DESCR) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                profileFlags |= HAS_DESCR;
            }
            if (hasDescr) {
                dataDescClass = getDescClass(descr);
                Object delete = PNone.NO_VALUE;
                Object set = lookupSet(dataDescClass);
                if (set == PNone.NO_VALUE) {
                    delete = lookupDelete(dataDescClass);
                }
                boolean hasDataDescr = set != PNone.NO_VALUE || delete != PNone.NO_VALUE;
                if (hasDataDescr && (profileFlags & HAS_DATA_DESCR) == 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    profileFlags |= HAS_DATA_DESCR;
                }
                if (hasDataDescr) {
                    Object get = lookupGet(dataDescClass);
                    if (PGuards.isCallableOrDescriptor(get)) {
                        // Only override if __get__ is defined, too, for compatibility with CPython.
                        return dispatch(frame, object, type, descr, get);
                    }
                }
            }
            Object value = readAttribute(object, key);
            boolean hasValue = value != PNone.NO_VALUE;
            if (hasValue && (profileFlags & HAS_VALUE) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                profileFlags |= HAS_VALUE;
            }
            if (hasValue) {
                return value;
            }
            if ((profileFlags & HAS_NO_VALUE) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                profileFlags |= HAS_NO_VALUE;
            }
            if (hasDescr) {
                if (object == PNone.NONE) {
                    if (descr instanceof PBuiltinFunction) {
                        // Special case for None object. We cannot call function.__get__(None,
                        // type(None)),
                        // because that would return an unbound method
                        return factory.get(inliningTarget).createBuiltinMethod(PNone.NONE, (PBuiltinFunction) descr);
                    }
                }
                Object get = lookupGet(dataDescClass);
                if (get == PNone.NO_VALUE) {
                    return descr;
                } else if (PGuards.isCallableOrDescriptor(get)) {
                    return dispatch(frame, object, type, descr, get);
                }
            }
            throw raiseNode.get(inliningTarget).raise(AttributeError, ErrorMessages.OBJ_P_HAS_NO_ATTR_S, object, key);
        }

        private Object readAttribute(Object object, Object key) {
            if (attrRead == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                attrRead = insert(ReadAttributeFromObjectNode.create());
            }
            return attrRead.execute(object, key);
        }

        private Object dispatch(VirtualFrame frame, Object object, Object type, Object descr, Object get) {
            if (dispatchGet == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                dispatchGet = insert(CallTernaryMethodNode.create());
            }
            return dispatchGet.execute(frame, get, descr, object, type);
        }

        private Object getDescClass(Object desc) {
            if (getDescClassNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getDescClassNode = insert(GetClassNode.create());
            }
            return getDescClassNode.executeCached(desc);
        }

        private Object lookupGet(Object dataDescClass) {
            if (lookupGetNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lookupGetNode = insert(LookupCallableSlotInMRONode.create(SpecialMethodSlot.Get));
            }
            return lookupGetNode.execute(dataDescClass);
        }

        private Object lookupDelete(Object dataDescClass) {
            if (lookupDeleteNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lookupDeleteNode = insert(LookupCallableSlotInMRONode.create(SpecialMethodSlot.Delete));
            }
            return lookupDeleteNode.execute(dataDescClass);
        }

        private Object lookupSet(Object dataDescClass) {
            if (lookupSetNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lookupSetNode = insert(LookupCallableSlotInMRONode.create(SpecialMethodSlot.Set));
            }
            return lookupSetNode.execute(dataDescClass);
        }

        // Note: we need this factory method as a workaround of a Truffle DSL bug
        @NeverDefault
        public static GetAttributeNode create() {
            return GetAttributeNodeFactory.create();
        }
    }

    @Builtin(name = J___SETATTR__, minNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    public abstract static class SetattrNode extends PythonTernaryBuiltinNode {

        @Specialization
        Object set(VirtualFrame frame, Object object, Object key, Object value,
                        @Bind("this") Node inliningTarget,
                        @Cached ObjectNodes.GenericSetAttrNode genericSetAttrNode,
                        @Cached WriteAttributeToObjectNode write) {
            genericSetAttrNode.execute(inliningTarget, frame, object, key, value, write);
            return PNone.NONE;
        }

        @NeverDefault
        public static SetattrNode create() {
            return ObjectBuiltinsFactory.SetattrNodeFactory.create();
        }
    }

    @Builtin(name = J___DELATTR__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class DelattrNode extends PythonBinaryBuiltinNode {
        @Specialization
        static PNone doIt(VirtualFrame frame, Object object, Object keyObj,
                        @Bind("this") Node inliningTarget,
                        @Cached LookupAttributeInMRONode.Dynamic getExisting,
                        @Cached GetClassNode getClassNode,
                        @Cached GetClassNode getDescClassNode,
                        @Cached("create(T___DELETE__)") LookupAttributeInMRONode lookupDeleteNode,
                        @Cached CallBinaryMethodNode callSetNode,
                        @Cached ReadAttributeFromObjectNode attrRead,
                        @Cached WriteAttributeToObjectNode writeNode,
                        @Cached CastToTruffleStringNode castKeyToStringNode,
                        @Cached PRaiseNode.Lazy raiseNode) {
            TruffleString key;
            try {
                key = castKeyToStringNode.execute(inliningTarget, keyObj);
            } catch (CannotCastException e) {
                throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.ATTR_NAME_MUST_BE_STRING, keyObj);
            }

            Object type = getClassNode.execute(inliningTarget, object);
            Object descr = getExisting.execute(type, key);
            if (descr != PNone.NO_VALUE) {
                Object dataDescClass = getDescClassNode.execute(inliningTarget, descr);
                Object set = lookupDeleteNode.execute(dataDescClass);
                if (PGuards.isCallable(set)) {
                    callSetNode.executeObject(frame, set, descr, object);
                    return PNone.NONE;
                }
            }
            Object currentValue = attrRead.execute(object, key);
            if (currentValue != PNone.NO_VALUE) {
                if (writeNode.execute(object, key, PNone.NO_VALUE)) {
                    return PNone.NONE;
                }
            }
            if (descr != PNone.NO_VALUE) {
                throw raiseNode.get(inliningTarget).raise(AttributeError, ErrorMessages.ATTR_S_READONLY, key);
            } else {
                throw raiseNode.get(inliningTarget).raise(AttributeError, ErrorMessages.OBJ_P_HAS_NO_ATTR_S, object, key);
            }
        }
    }

    @Builtin(name = J___DICT__, minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2, isGetter = true, isSetter = true)
    public abstract static class DictNode extends PythonBinaryBuiltinNode {

        protected static boolean isExactObject(Node inliningTarget, InlineIsBuiltinClassProfile profile, Object clazz) {
            return profile.profileIsBuiltinClass(inliningTarget, clazz, PythonBuiltinClassType.PythonObject);
        }

        protected static boolean isAnyBuiltinButModule(Node inliningTarget, IsOtherBuiltinClassProfile profile, Object clazz) {
            // any builtin class except Modules
            return profile.profileIsOtherBuiltinClass(inliningTarget, clazz, PythonBuiltinClassType.PythonModule);
        }

        @Specialization(guards = {"!isAnyBuiltinButModule(inliningTarget, otherBuiltinClassProfile, selfClass)", //
                        "!isExactObject(inliningTarget, isBuiltinClassProfile, selfClass)", "isNoValue(none)"}, limit = "1")
        static Object dict(VirtualFrame frame, Object self, @SuppressWarnings("unused") PNone none,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Exclusive @Cached IsOtherBuiltinClassProfile otherBuiltinClassProfile,
                        @SuppressWarnings("unused") @Exclusive @Cached InlineIsBuiltinClassProfile isBuiltinClassProfile,
                        @SuppressWarnings("unused") @Exclusive @Cached GetClassNode getClassNode,
                        @Bind("getClassNode.execute(inliningTarget, self)") Object selfClass,
                        @Exclusive @Cached GetBaseClassNode getBaseNode,
                        @Cached("createForLookupOfUnmanagedClasses(T___DICT__)") @Shared LookupAttributeInMRONode getDescrNode,
                        @Cached DescrGetNode getNode,
                        @Cached GetOrCreateDictNode getDict,
                        @Exclusive @Cached InlinedBranchProfile branchProfile) {
            // typeobject.c#subtype_getdict()
            Object func = getDescrFromBuiltinBase(inliningTarget, selfClass, getBaseNode, getDescrNode);
            if (func != null) {
                branchProfile.enter(inliningTarget);
                return getNode.execute(frame, func, self);
            }

            return getDict.execute(inliningTarget, self);
        }

        @Specialization(guards = {"!isAnyBuiltinButModule(inliningTarget, otherBuiltinClassProfile, selfClass)", //
                        "!isExactObject(inliningTarget, isBuiltinClassProfile, selfClass)", "!isPythonModule(self)"}, limit = "1")
        static Object dict(VirtualFrame frame, Object self, PDict dict,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Exclusive @Cached IsOtherBuiltinClassProfile otherBuiltinClassProfile,
                        @SuppressWarnings("unused") @Exclusive @Cached InlineIsBuiltinClassProfile isBuiltinClassProfile,
                        @Exclusive @Cached GetClassNode getClassNode,
                        @SuppressWarnings("unused") @Bind("getClassNode.execute(inliningTarget, self)") Object selfClass,
                        @Exclusive @Cached GetBaseClassNode getBaseNode,
                        @Shared @Cached("createForLookupOfUnmanagedClasses(T___DICT__)") LookupAttributeInMRONode getDescrNode,
                        @Cached DescrSetNode setNode,
                        @Cached SetDictNode setDict,
                        @Exclusive @Cached InlinedBranchProfile branchProfile) {
            // typeobject.c#subtype_setdict()
            Object func = getDescrFromBuiltinBase(inliningTarget, getClassNode.execute(inliningTarget, self), getBaseNode, getDescrNode);
            if (func != null) {
                branchProfile.enter(inliningTarget);
                return setNode.execute(frame, func, self, dict);
            }

            setDict.execute(inliningTarget, self, dict);
            return PNone.NONE;
        }

        @Specialization
        static Object dict(VirtualFrame frame, PythonObject self, @SuppressWarnings("unused") DescriptorDeleteMarker marker,
                        @Bind("this") Node inliningTarget,
                        @Exclusive @Cached GetClassNode getClassNode,
                        @Exclusive @Cached GetBaseClassNode getBaseNode,
                        @Shared @Cached("createForLookupOfUnmanagedClasses(T___DICT__)") LookupAttributeInMRONode getDescrNode,
                        @Cached DescrDeleteNode deleteNode,
                        @Cached DeleteDictNode deleteDictNode,
                        @Exclusive @Cached InlinedBranchProfile branchProfile) {
            // typeobject.c#subtype_setdict()
            Object func = getDescrFromBuiltinBase(inliningTarget, getClassNode.execute(inliningTarget, self), getBaseNode, getDescrNode);
            if (func != null) {
                branchProfile.enter(inliningTarget);
                return deleteNode.execute(frame, func, self);
            }
            deleteDictNode.execute(self);
            return PNone.NONE;
        }

        /**
         * see typeobject.c#get_builtin_base_with_dict()
         */
        private static Object getDescrFromBuiltinBase(Node inliningTarget, Object type, GetBaseClassNode getBaseNode, LookupAttributeInMRONode getDescrNode) {
            Object t = type;
            Object base = getBaseNode.execute(inliningTarget, t);
            while (base != null) {
                if (t instanceof PythonBuiltinClass) {
                    Object func = getDescrNode.execute(t);
                    if (func != PNone.NO_VALUE) {
                        return func;
                    }
                }
                t = base;
                base = getBaseNode.execute(inliningTarget, t);
            }
            return null;
        }

        @Specialization(guards = {"!isNoValue(mapping)", "!isDict(mapping)", "!isDeleteMarker(mapping)"})
        static Object dict(@SuppressWarnings("unused") Object self, Object mapping,
                        @Shared @Cached PRaiseNode raiseNode) {
            throw raiseNode.raise(TypeError, ErrorMessages.DICT_MUST_BE_SET_TO_DICT, mapping);
        }

        @Specialization(guards = "isFallback(self, mapping, inliningTarget, getClassNode, otherBuiltinClassProfile, isBuiltinClassProfile)", limit = "1")
        @SuppressWarnings("unused")
        static Object raise(Object self, Object mapping,
                        @Bind("this") Node inliningTarget,
                        @Exclusive @Cached IsOtherBuiltinClassProfile otherBuiltinClassProfile,
                        @Exclusive @Cached InlineIsBuiltinClassProfile isBuiltinClassProfile,
                        @Exclusive @Cached GetClassNode getClassNode,
                        @Shared @Cached PRaiseNode raiseNode) {
            throw raiseNode.raise(AttributeError, ErrorMessages.OBJ_P_HAS_NO_ATTR_S, self, "__dict__");
        }

        static boolean isFallback(Object self, Object mapping, Node inliningTarget,
                        GetClassNode getClassNode,
                        IsOtherBuiltinClassProfile otherBuiltinClassProfile,
                        InlineIsBuiltinClassProfile isBuiltinClassProfile) {
            Object selfClass = getClassNode.execute(inliningTarget, self);
            boolean classFilter = !isAnyBuiltinButModule(inliningTarget, otherBuiltinClassProfile, selfClass) && !isExactObject(inliningTarget, isBuiltinClassProfile, selfClass);
            return !((classFilter && isNoValue(mapping)) ||
                            (classFilter && !isPythonModule(self) && isDict(mapping)) ||
                            (isPythonObject(self) && isDeleteMarker(mapping)) ||
                            (!isNoValue(mapping) && !isDict(mapping) && !isDeleteMarker(mapping)));
        }
    }

    @Builtin(name = J___FORMAT__, minNumOfPositionalArgs = 2, parameterNames = {"$self", "format_spec"})
    @ArgumentClinic(name = "format_spec", conversion = ClinicConversion.TString)
    @GenerateNodeFactory
    abstract static class FormatNode extends PythonBinaryClinicBuiltinNode {
        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return FormatNodeClinicProviderGen.INSTANCE;
        }

        @Specialization(guards = "!formatString.isEmpty()")
        static Object format(Object self, @SuppressWarnings("unused") TruffleString formatString,
                        @Cached PRaiseNode raiseNode) {
            throw raiseNode.raise(PythonBuiltinClassType.TypeError, ErrorMessages.UNSUPPORTED_FORMAT_STRING_PASSED_TO_P_FORMAT, self);
        }

        @Specialization(guards = "formatString.isEmpty()")
        static Object format(VirtualFrame frame, Object self, @SuppressWarnings("unused") TruffleString formatString,
                        @Cached("create(Str)") LookupAndCallUnaryNode strCall) {
            return strCall.executeObject(frame, self);
        }
    }

    @Builtin(name = J_RICHCMP, minNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class RichCompareNode extends PythonTernaryBuiltinNode {
        protected static final int NO_SLOW_PATH = Integer.MAX_VALUE;
        @CompilationFinal private boolean seenNonBoolean = false;

        static BinaryComparisonNode createOp(int op) {
            switch (op) {
                case 0:
                    return BinaryComparisonNodeFactory.LtNodeGen.create();
                case 4:
                    return BinaryComparisonNodeFactory.GtNodeGen.create();
                case 2:
                    return BinaryComparisonNodeFactory.EqNodeGen.create();
                case 5:
                    return BinaryComparisonNodeFactory.GeNodeGen.create();
                case 1:
                    return BinaryComparisonNodeFactory.LeNodeGen.create();
                case 3:
                    return BinaryComparisonNodeFactory.NeNodeGen.create();
                default:
                    throw new RuntimeException("unexpected operation: " + op);
            }
        }

        @Specialization(guards = "op == cachedOp", limit = "NO_SLOW_PATH")
        @SuppressWarnings("truffle-static-method")
        boolean richcmp(VirtualFrame frame, Object left, Object right, @SuppressWarnings("unused") int op,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached("op") int cachedOp,
                        @Cached("createOp(op)") BinaryComparisonNode node,
                        @Cached CoerceToBooleanNode.YesNode castToBooleanNode) {
            if (!seenNonBoolean) {
                try {
                    return node.executeBool(frame, left, right);
                } catch (UnexpectedResultException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenNonBoolean = true;
                    return castToBooleanNode.executeBoolean(frame, inliningTarget, e.getResult());
                }
            } else {
                return castToBooleanNode.executeBoolean(frame, inliningTarget, node.executeObject(frame, left, right));
            }
        }
    }

    @Builtin(name = J___INIT_SUBCLASS__, minNumOfPositionalArgs = 1, isClassmethod = true)
    @GenerateNodeFactory
    abstract static class InitSubclass extends PythonUnaryBuiltinNode {
        @Specialization
        static PNone initSubclass(@SuppressWarnings("unused") Object self) {
            return PNone.NONE;
        }
    }

    @Builtin(name = J___SUBCLASSHOOK__, minNumOfPositionalArgs = 1, declaresExplicitSelf = true, takesVarArgs = true, takesVarKeywordArgs = true, isClassmethod = true)
    @GenerateNodeFactory
    abstract static class SubclassHookNode extends PythonVarargsBuiltinNode {
        @Specialization
        @SuppressWarnings("unused")
        static Object notImplemented(Object self, Object[] arguments, PKeyword[] keywords) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        @Override
        public Object varArgExecute(VirtualFrame frame, Object self, Object[] arguments, PKeyword[] keywords) throws VarargsBuiltinDirectInvocationNotSupported {
            return PNotImplemented.NOT_IMPLEMENTED;
        }
    }

    @Builtin(name = J___SIZEOF__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class SizeOfNode extends PythonUnaryBuiltinNode {
        @Specialization
        static Object doit(VirtualFrame frame, Object obj,
                        @Bind("this") Node inliningTarget,
                        @Cached GetClassNode getClassNode,
                        @Cached PyObjectSizeNode sizeNode,
                        @Cached PyObjectLookupAttr lookupAttr,
                        @Cached TypeNodes.GetBasicSizeNode getBasicSizeNode,
                        @Cached TypeNodes.GetItemSizeNode getItemSizeNode) {
            Object cls = getClassNode.execute(inliningTarget, obj);
            long size = 0;
            long itemsize = getItemSizeNode.execute(inliningTarget, cls);
            if (itemsize != 0) {
                Object objLen = lookupAttr.execute(frame, inliningTarget, obj, T___LEN__);
                if (objLen != PNone.NO_VALUE) {
                    size = sizeNode.execute(frame, inliningTarget, obj) * itemsize;
                }
            }
            size += getBasicSizeNode.execute(inliningTarget, cls);
            return size;
        }
    }

    @Builtin(name = J___REDUCE__, minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    // Note: this must not inherit from PythonUnaryBuiltinNode, i.e. must not be AST inlined.
    // The CommonReduceNode seems to need a fresh frame, otherwise it can mess up the existing one.
    public abstract static class ReduceNode extends PythonBuiltinNode {
        @Specialization
        @SuppressWarnings("unused")
        static Object doit(VirtualFrame frame, Object obj, @SuppressWarnings("unused") Object ignored,
                        @Bind("this") Node inliningTarget,
                        @Cached ObjectNodes.CommonReduceNode commonReduceNode) {
            return commonReduceNode.execute(frame, inliningTarget, obj, 0);
        }
    }

    @Builtin(name = J___REDUCE_EX__, minNumOfPositionalArgs = 2, numOfPositionalOnlyArgs = 2, parameterNames = {"$self", "protocol"})
    @ArgumentClinic(name = "protocol", conversion = ArgumentClinic.ClinicConversion.Int)
    @GenerateNodeFactory
    // Note: this must not inherit from PythonBinaryClinicBuiltinNode, i.e. must not be AST inlined.
    // The CommonReduceNode seems to need a fresh frame, otherwise it can mess up the existing one.
    public abstract static class ReduceExNode extends PythonClinicBuiltinNode {
        static final Object REDUCE_FACTORY = ObjectBuiltinsFactory.ReduceNodeFactory.getInstance();

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return ReduceExNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        @SuppressWarnings("unused")
        static Object doit(VirtualFrame frame, Object obj, int proto,
                        @Bind("this") Node inliningTarget,
                        @Cached PyObjectLookupAttr lookupAttr,
                        @Cached CallNode callNode,
                        @Cached InlinedConditionProfile reduceProfile,
                        @Cached ObjectNodes.CommonReduceNode commonReduceNode) {
            Object _reduce = lookupAttr.execute(frame, inliningTarget, obj, T___REDUCE__);
            if (reduceProfile.profile(inliningTarget, _reduce != PNone.NO_VALUE)) {
                // Check if __reduce__ has been overridden:
                // "type(obj).__reduce__ is not object.__reduce__"
                if (!(_reduce instanceof PBuiltinMethod) || ((PBuiltinMethod) _reduce).getBuiltinFunction().getBuiltinNodeFactory() != REDUCE_FACTORY) {
                    return callNode.execute(frame, _reduce);
                }
            }
            return commonReduceNode.execute(frame, inliningTarget, obj, proto);
        }
    }

    @Builtin(name = J___DIR__, minNumOfPositionalArgs = 1, doc = "__dir__ for generic objects\n\n\tReturns __dict__, __class__ and recursively up the\n\t__class__.__bases__ chain.")
    @GenerateNodeFactory
    public abstract static class DirNode extends PythonBuiltinNode {
        @Specialization
        static Object dir(VirtualFrame frame, Object obj,
                        @Bind("this") Node inliningTarget,
                        @Cached PyObjectLookupAttr lookupAttrNode,
                        @Cached CallNode callNode,
                        @Cached GetClassNode getClassNode,
                        @Cached IsSubtypeNode isSubtypeNode,
                        @Cached com.oracle.graal.python.builtins.objects.type.TypeBuiltins.DirNode dirNode,
                        @Cached PythonObjectFactory factory) {
            PSet names = factory.createSet();
            Object updateCallable = lookupAttrNode.execute(frame, inliningTarget, names, T_UPDATE);
            Object ns = lookupAttrNode.execute(frame, inliningTarget, obj, T___DICT__);
            if (isSubtypeNode.execute(frame, getClassNode.execute(inliningTarget, ns), PythonBuiltinClassType.PDict)) {
                callNode.execute(frame, updateCallable, ns);
            }
            Object klass = lookupAttrNode.execute(frame, inliningTarget, obj, T___CLASS__);
            if (klass != PNone.NO_VALUE) {
                callNode.execute(frame, updateCallable, dirNode.execute(frame, klass));
            }
            return names;
        }
    }

}
