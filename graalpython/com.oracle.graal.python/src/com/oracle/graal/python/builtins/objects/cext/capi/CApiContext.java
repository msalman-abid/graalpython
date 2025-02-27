/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___FILE__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.T___LIBRARY__;
import static com.oracle.graal.python.nodes.StringLiterals.J_LLVM_LANGUAGE;
import static com.oracle.graal.python.nodes.StringLiterals.J_NFI_LANGUAGE;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Pair;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltinRegistry;
import com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltins.CApiBuiltinExecutable;
import com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltins.CApiCallPath;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodesFactory.CreateModuleNodeGen;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.NativeToPythonNode;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.PointerContainer;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.CheckFunctionResultNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtContext;
import com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException.ApiInitException;
import com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException.ImportException;
import com.oracle.graal.python.builtins.objects.cext.structs.CFields;
import com.oracle.graal.python.builtins.objects.cext.structs.CStructAccess;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.frame.PFrame;
import com.oracle.graal.python.builtins.objects.module.PythonModule;
import com.oracle.graal.python.builtins.objects.thread.PLock;
import com.oracle.graal.python.builtins.objects.type.PythonManagedClass;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.IndirectCallNode;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.object.GetClassNodeGen;
import com.oracle.graal.python.runtime.ExecutionContext.IndirectCallContext;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonContext.GetThreadStateNode;
import com.oracle.graal.python.runtime.PythonContext.PythonThreadState;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.graal.python.util.Supplier;
import com.oracle.graal.python.util.WeakIdentityHashMap;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInterface;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.Source.SourceBuilder;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.nfi.api.SignatureLibrary;

public final class CApiContext extends CExtContext {

    public static final String LOGGER_CAPI_NAME = "capi";

    /**
     * The default C-level call recursion limit like {@code Py_DEFAULT_RECURSION_LIMIT}.
     */
    public static final int DEFAULT_RECURSION_LIMIT = 1000;
    private static final TruffleLogger LOGGER = PythonLanguage.getLogger(LOGGER_CAPI_NAME);

    /* a random number between 1 and 20 */
    private static final int MAX_COLLECTION_RETRIES = 17;

    /** Total amount of allocated native memory (in bytes). */
    private long allocatedMemory = 0;

    private Map<Object, AllocInfo> allocatedNativeMemory;
    private TraceMallocDomain[] traceMallocDomains;

    /** Container of pointers that have seen to be free'd. */
    private Map<Object, AllocInfo> freedNativeMemory;

    /**
     * This cache is used to cache native wrappers for frequently used primitives. This is strictly
     * defined to be the range {@code [-5, 256]}. CPython does exactly the same (see
     * {@code PyLong_FromLong}; implemented in macro {@code CHECK_SMALL_INT}).
     */
    @CompilationFinal(dimensions = 1) private final PrimitiveNativeWrapper[] primitiveNativeWrapperCache;

    /** same as {@code moduleobject.c: max_module_number} */
    private long maxModuleNumber;

    /** Same as {@code import.c: extensions} but we don't keep a PDict; just a bare Java HashMap. */
    private final HashMap<Pair<TruffleString, TruffleString>, Object> extensions = new HashMap<>(4);

    /** corresponds to {@code unicodeobject.c: interned} */
    private PDict internedUnicode;
    private final ArrayList<Object> modulesByIndex = new ArrayList<>(0);

    public final HashMap<Long, PLock> locks = new HashMap<>();
    public final AtomicLong lockId = new AtomicLong();

    /**
     * Thread local storage for PyThread_tss_* APIs
     */
    private final ConcurrentHashMap<Long, ThreadLocal<Object>> tssStorage = new ConcurrentHashMap<>();
    /**
     * Next key that will be allocated byt PyThread_tss_create
     */
    private final AtomicLong nextTssKey = new AtomicLong();

    public Object timezoneType;

    private record ClosureInfo(Object closure, Object delegate, Object executable, long pointer) {
    }

    /*
     * The key is the executable instance, i.e., an instance of a class that exports the
     * InteropLibrary.
     */
    private final HashMap<Object, ClosureInfo> callableClosureByExecutable = new HashMap<>();
    private final HashMap<Long, ClosureInfo> callableClosures = new HashMap<>();

    /**
     * This list holds a strong reference to all loaded extension libraries to keep the library
     * objects alive. This is necessary because NFI will {@code dlclose} the library (and thus
     * {@code munmap} all code) if the library object is no longer reachable. However, it can happen
     * that we still store raw function pointers (as Java {@code long} values) in a native object
     * that is referenced by a
     * {@link com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.NativeObjectReference}.
     * For example, the {code tp_dealloc} functions may be executed a long time after all managed
     * objects of the extension died and the native library has been {@code dlclosed}'d.
     *
     * Since we have no control over the timing when certain garbage will be collected, we need to
     * ensure that the code is still mapped.
     */
    private final List<Object> loadedExtensions = new LinkedList<>();

    public static TruffleLogger getLogger(Class<?> clazz) {
        return PythonLanguage.getLogger(LOGGER_CAPI_NAME + "." + clazz.getSimpleName());
    }

    public CApiContext(PythonContext context, Object llvmLibrary, boolean useNativeBackend) {
        super(context, llvmLibrary, useNativeBackend);

        // initialize primitive native wrapper cache
        primitiveNativeWrapperCache = new PrimitiveNativeWrapper[262];
        for (int i = 0; i < primitiveNativeWrapperCache.length; i++) {
            PrimitiveNativeWrapper nativeWrapper = PrimitiveNativeWrapper.createInt(i - 5);
            CApiTransitions.incRef(nativeWrapper, PythonNativeWrapper.IMMORTAL_REFCNT);
            primitiveNativeWrapperCache[i] = nativeWrapper;
        }
    }

    public long getAndIncMaxModuleNumber() {
        return maxModuleNumber++;
    }

    @TruffleBoundary
    void addLoadedExtensionLibrary(Object nativeLibrary) {
        loadedExtensions.add(nativeLibrary);
    }

    @TruffleBoundary
    public static Object asHex(Object ptr) {
        if (ptr instanceof Number) {
            return "0x" + Long.toHexString(((Number) ptr).longValue());
        }
        return Objects.toString(ptr);
    }

    public PDict getInternedUnicode() {
        return internedUnicode;
    }

    public void setInternedUnicode(PDict internedUnicode) {
        this.internedUnicode = internedUnicode;
    }

    /**
     * Tries to convert the object to a pointer (type: {@code long}) to avoid materialization of
     * pointer objects. If that is not possible, the object will be returned as given.
     */
    public static Object asPointer(Object ptr, InteropLibrary lib) {
        if (lib.isPointer(ptr)) {
            try {
                return lib.asPointer(ptr);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }
        return ptr;
    }

    public TraceMallocDomain getTraceMallocDomain(int domainIdx) {
        return traceMallocDomains[domainIdx];
    }

    public int findOrCreateTraceMallocDomain(int id) {
        int oldLength;
        if (traceMallocDomains != null) {
            for (int i = 0; i < traceMallocDomains.length; i++) {
                if (traceMallocDomains[i].id == id) {
                    return i;
                }
            }

            // create new domain
            oldLength = traceMallocDomains.length;
            traceMallocDomains = Arrays.copyOf(traceMallocDomains, traceMallocDomains.length + 1);
        } else {
            oldLength = 0;
            traceMallocDomains = new TraceMallocDomain[1];
        }
        traceMallocDomains[oldLength] = new TraceMallocDomain(id);
        return oldLength;
    }

    public long nextTssKey() {
        return nextTssKey.incrementAndGet();
    }

    @TruffleBoundary
    public Object tssGet(long key) {
        ThreadLocal<Object> local = tssStorage.get(key);
        if (local != null) {
            return local.get();
        }
        return null;
    }

    @TruffleBoundary
    public void tssSet(long key, Object object) {
        tssStorage.computeIfAbsent(key, (k) -> new ThreadLocal<>()).set(object);
    }

    @TruffleBoundary
    public void tssDelete(long key) {
        tssStorage.remove(key);
    }

    public PrimitiveNativeWrapper getCachedPrimitiveNativeWrapper(int i) {
        assert CApiGuards.isSmallInteger(i);
        PrimitiveNativeWrapper primitiveNativeWrapper = primitiveNativeWrapperCache[i + 5];
        assert primitiveNativeWrapper.getRefCount() > 0;
        return primitiveNativeWrapper;
    }

    public PrimitiveNativeWrapper getCachedPrimitiveNativeWrapper(long l) {
        assert CApiGuards.isSmallLong(l);
        return getCachedPrimitiveNativeWrapper((int) l);
    }

    @TruffleBoundary
    @Override
    protected Store initializeSymbolCache() {
        PythonLanguage language = getContext().getLanguage();
        Shape symbolCacheShape = language.getCApiSymbolCacheShape();
        // We will always get an empty shape from the language and we do always add same key-value
        // pairs (in the same order). So, in the end, each context should get the same shape.
        Store s = new Store(symbolCacheShape);
        for (NativeCAPISymbol sym : NativeCAPISymbol.getValues()) {
            DynamicObjectLibrary.getUncached().put(s, sym, PNone.NO_VALUE);
        }
        return s;
    }

    public Object getModuleByIndex(int i) {
        if (i < modulesByIndex.size()) {
            return modulesByIndex.get(i);
        }
        return null;
    }

    @TruffleBoundary
    public AllocInfo traceFree(Object ptr, @SuppressWarnings("unused") PFrame.Reference curFrame, @SuppressWarnings("unused") TruffleString clazzName) {
        if (allocatedNativeMemory == null) {
            allocatedNativeMemory = new HashMap<>();
        }
        if (freedNativeMemory == null) {
            freedNativeMemory = new HashMap<>();
        }
        AllocInfo allocatedValue = allocatedNativeMemory.remove(ptr);
        Object freedValue = freedNativeMemory.put(ptr, allocatedValue);
        if (freedValue != null) {
            LOGGER.severe(PythonUtils.formatJString("freeing memory that was already free'd %s (double-free)", asHex(ptr)));
        } else if (allocatedValue == null) {
            LOGGER.info(PythonUtils.formatJString("freeing non-allocated memory %s (maybe a double-free or we didn't trace the allocation)", asHex(ptr)));
        }
        return allocatedValue;
    }

    @TruffleBoundary
    public void traceAlloc(Object ptr, PFrame.Reference curFrame, TruffleString clazzName, long size) {
        if (allocatedNativeMemory == null) {
            allocatedNativeMemory = new HashMap<>();
        }
        Object value = allocatedNativeMemory.put(ptr, new AllocInfo(clazzName, curFrame, size));
        if (freedNativeMemory != null) {
            freedNativeMemory.remove(ptr);
        }
        assert value == null : "native memory allocator reserved same memory twice";
    }

    @SuppressWarnings("unused")
    public void trackObject(Object ptr, PFrame.Reference curFrame, TruffleString clazzName) {
        // TODO(fa): implement tracking of container objects for cycle detection
    }

    @SuppressWarnings("unused")
    public void untrackObject(Object ptr, PFrame.Reference curFrame, TruffleString clazzName) {
        // TODO(fa): implement untracking of container objects
    }

    /**
     * Use this method to register memory that is known to be allocated (i.e. static variables like
     * types). This is basically the same as
     * {@link #traceAlloc(Object, PFrame.Reference, TruffleString, long)} but does not consider it
     * to be an error if the memory is already allocated.
     */
    @TruffleBoundary
    public void traceStaticMemory(Object ptr, PFrame.Reference curFrame, TruffleString clazzName) {
        if (allocatedNativeMemory == null) {
            allocatedNativeMemory = new HashMap<>();
        }
        if (freedNativeMemory != null) {
            freedNativeMemory.remove(ptr);
        }
        allocatedNativeMemory.put(ptr, new AllocInfo(curFrame, clazzName));
    }

    @TruffleBoundary
    public boolean isAllocated(Object ptr) {
        if (freedNativeMemory != null && freedNativeMemory.containsKey(ptr)) {
            assert !allocatedNativeMemory.containsKey(ptr);
            return false;
        }
        return true;
    }

    public void increaseMemoryPressure(VirtualFrame frame, Node inliningTarget, GetThreadStateNode getThreadStateNode, IndirectCallNode caller, long size) {
        PythonContext context = getContext();
        if (allocatedMemory + size <= context.getOption(PythonOptions.MaxNativeMemory)) {
            allocatedMemory += size;
            return;
        }

        PythonThreadState threadState = getThreadStateNode.execute(inliningTarget, context);
        Object savedState = IndirectCallContext.enter(frame, threadState, caller);
        try {
            triggerGC(context, size, caller);
        } finally {
            IndirectCallContext.exit(frame, threadState, savedState);
        }
    }

    @TruffleBoundary
    public void triggerGC(PythonContext context, long size, NodeInterface caller) {
        long delay = 0;
        for (int retries = 0; retries < MAX_COLLECTION_RETRIES; retries++) {
            delay += 50;
            doGc(delay);
            CApiTransitions.pollReferenceQueue();
            PythonContext.triggerAsyncActions((Node) caller);
            if (allocatedMemory + size <= context.getOption(PythonOptions.MaxNativeMemory)) {
                allocatedMemory += size;
                return;
            }
        }
        throw new OutOfMemoryError("native memory");
    }

    public void reduceMemoryPressure(long size) {
        allocatedMemory -= size;
    }

    @TruffleBoundary
    private static void doGc(long millis) {
        LOGGER.fine("full GC due to native memory");
        PythonUtils.forceFullGC();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException x) {
            // Restore interrupt status
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tests if any read/write access to the given pointer object is invalid. This should be used to
     * test access before getting the type of reference count of native objects.
     */
    public void checkAccess(Object pointerObject, InteropLibrary lib) {
        if (getContext().getOption(PythonOptions.TraceNativeMemory)) {
            Object ptrVal = CApiContext.asPointer(pointerObject, lib);
            if (!isAllocated(ptrVal)) {
                LOGGER.severe(() -> "Access to invalid memory at " + CApiContext.asHex(ptrVal));
            }
        }
    }

    public static final class AllocInfo {
        public final TruffleString typeName;
        public final PFrame.Reference allocationSite;
        public final long size;

        public AllocInfo(TruffleString typeName, PFrame.Reference allocationSite, long size) {
            this.typeName = typeName;
            this.allocationSite = allocationSite;
            this.size = size;
        }

        public AllocInfo(PFrame.Reference allocationSite, TruffleString typeName) {
            this(typeName, allocationSite, -1);
        }
    }

    public static final class TraceMallocDomain {
        private final int id;
        private final EconomicMap<Object, Long> allocatedMemory;

        public TraceMallocDomain(int id) {
            this.id = id;
            this.allocatedMemory = EconomicMap.create();
        }

        @TruffleBoundary
        public void track(Object pointerObject, long size) {
            allocatedMemory.put(pointerObject, size);
        }

        @TruffleBoundary
        public long untrack(Object pointerObject) {
            Long value = allocatedMemory.removeKey(pointerObject);
            if (value != null) {
                // TODO(fa): be more restrictive?
                return value;
            }
            return 0;
        }

        public int getId() {
            return id;
        }
    }

    /**
     * This represents whether the current process has already loaded an instance of the native CAPI
     * extensions - this can only be loaded once per process.
     */
    private static AtomicBoolean nativeCAPILoaded = new AtomicBoolean();
    private static AtomicBoolean warnedSecondContexWithNativeCAPI = new AtomicBoolean();

    @TruffleBoundary
    public static CApiContext ensureCapiWasLoaded(Node node, PythonContext context, TruffleString name, TruffleString path) throws IOException, ImportException, ApiInitException {
        if (!context.hasCApiContext()) {
            Env env = context.getEnv();
            InteropLibrary U = InteropLibrary.getUncached();

            TruffleFile homePath = env.getInternalTruffleFile(context.getCAPIHome().toJavaStringUncached());
            // e.g. "libpython-native.so"
            String libName = context.getLLVMSupportExt("python");
            TruffleFile capiFile = homePath.resolve(libName).getCanonicalFile(LinkOption.NOFOLLOW_LINKS);
            try {
                SourceBuilder capiSrcBuilder;
                final boolean useNative;
                if (PythonOptions.NativeModules.getValue(env.getOptions())) {
                    useNative = nativeCAPILoaded.compareAndSet(false, true);
                    if (!useNative && warnedSecondContexWithNativeCAPI.compareAndSet(false, true)) {
                        LOGGER.warning("GraalPy option 'NativeModules' is set to true, " +
                                        "but only one context in the process can use native modules, " +
                                        "second and other contexts fallback to NativeModules=false and " +
                                        "will use LLVM bitcode execution via GraalVM LLVM.");
                    }
                } else {
                    useNative = false;
                }
                if (useNative) {
                    context.ensureNFILanguage(node, "NativeModules", "true");
                    capiSrcBuilder = Source.newBuilder(J_NFI_LANGUAGE, "load(RTLD_GLOBAL) \"" + capiFile.getPath() + "\"", "<libpython>");
                } else {
                    context.ensureLLVMLanguage(node);
                    capiSrcBuilder = Source.newBuilder(J_LLVM_LANGUAGE, capiFile);
                }
                if (!context.getLanguage().getEngineOption(PythonOptions.ExposeInternalSources)) {
                    capiSrcBuilder.internal(true);
                }
                LOGGER.config(() -> "loading CAPI from " + capiFile + " as " + (useNative ? "native" : "bitcode"));
                CallTarget capiLibraryCallTarget = context.getEnv().parseInternal(capiSrcBuilder.build());

                Object capiLibrary = capiLibraryCallTarget.call();
                Object initFunction = U.readMember(capiLibrary, "initialize_graal_capi");
                CApiContext cApiContext = new CApiContext(context, capiLibrary, useNative);
                context.setCApiContext(cApiContext);
                if (!U.isExecutable(initFunction)) {
                    Object signature = PythonContext.get(null).getEnv().parseInternal(Source.newBuilder(J_NFI_LANGUAGE, "(ENV,(SINT32):POINTER):VOID", "exec").build()).call();
                    initFunction = SignatureLibrary.getUncached().bind(signature, initFunction);
                    U.execute(initFunction, new GetBuiltin());
                } else {
                    U.execute(initFunction, new PointerContainer(0), new GetBuiltin());
                }

                assert CApiCodeGen.assertBuiltins(capiLibrary);
                PyDateTimeCAPIWrapper.initWrapper(cApiContext);
                context.runCApiHooks();

                return cApiContext;
            } catch (PException e) {
                /*
                 * Python exceptions that occur during the C API initialization are just passed
                 * through
                 */
                throw e;
            } catch (RuntimeException | UnsupportedMessageException | ArityException | UnknownIdentifierException | UnsupportedTypeException e) {
                // we cannot really check if we truly need native access, so
                // when the abi contains "managed" we assume we do not
                if (!libName.contains("managed") && !context.isNativeAccessAllowed()) {
                    throw new ImportException(null, name, path, ErrorMessages.NATIVE_ACCESS_NOT_ALLOWED);
                }
                throw new ApiInitException(e);
            }
        }
        return context.getCApiContext();
    }

    @TruffleBoundary
    public void finalizeCapi() {
        if (useNativeBackend && getLLVMLibrary() != null) {
            try {
                Object finalizeFunction = InteropLibrary.getUncached().readMember(getLLVMLibrary(), "finalizeCAPI");
                Object signature = PythonContext.get(null).getEnv().parseInternal(Source.newBuilder(J_NFI_LANGUAGE, "():VOID", "exec").build()).call();
                SignatureLibrary.getUncached().call(signature, finalizeFunction);
            } catch (InteropException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }
    }

    @TruffleBoundary
    public Object initCApiModule(Node location, Object sharedLibrary, TruffleString initFuncName, ModuleSpec spec, InteropLibrary llvmInteropLib, CheckFunctionResultNode checkFunctionResultNode)
                    throws UnsupportedMessageException, ArityException, UnsupportedTypeException, ImportException {
        PythonContext context = getContext();
        CApiContext cApiContext = context.getCApiContext();
        Object pyinitFunc;
        try {
            pyinitFunc = llvmInteropLib.readMember(sharedLibrary, initFuncName.toJavaStringUncached());
        } catch (UnknownIdentifierException | UnsupportedMessageException e1) {
            throw new ImportException(null, spec.name, spec.path, ErrorMessages.NO_FUNCTION_FOUND, "", initFuncName, spec.path);
        }
        Object nativeResult;
        try {
            nativeResult = InteropLibrary.getUncached().execute(pyinitFunc);
        } catch (UnsupportedMessageException e) {
            Object signature = PythonContext.get(null).getEnv().parseInternal(Source.newBuilder(J_NFI_LANGUAGE, "():POINTER", "exec").build()).call();
            Object bound = SignatureLibrary.getUncached().bind(signature, pyinitFunc);
            nativeResult = InteropLibrary.getUncached().execute(bound);
        } catch (ArityException e) {
            // In case of multi-phase init, the init function may take more than one argument.
            // However, CPython gracefully ignores that. So, we pass just NULL pointers.
            Object[] arguments = new Object[e.getExpectedMinArity()];
            Arrays.fill(arguments, PNone.NO_VALUE);
            nativeResult = InteropLibrary.getUncached().execute(pyinitFunc, arguments);
        }

        checkFunctionResultNode.execute(context, initFuncName, nativeResult);

        Object result = NativeToPythonNode.executeUncached(nativeResult);
        if (!(result instanceof PythonModule)) {
            // Multi-phase extension module initialization

            /*
             * See 'importdl.c: _PyImport_LoadDynamicModuleWithSpec' before
             * 'PyModule_FromDefAndSpec' is called. The 'PyModule_FromDefAndSpec' would initialize
             * the module def as Python object but before that, CPython explicitly checks if the
             * init function did this initialization by calling 'PyModuleDef_Init' on it. So, we
             * must do it here because 'CreateModuleNode' should just ignore this case.
             */
            Object clazz = GetClassNodeGen.getUncached().execute(null, result);
            if (clazz == PNone.NO_VALUE) {
                throw PRaiseNode.raiseUncached(location, PythonBuiltinClassType.SystemError, ErrorMessages.INIT_FUNC_RETURNED_UNINT_OBJ, initFuncName);
            }

            return CreateModuleNodeGen.getUncached().execute(cApiContext, spec, result, sharedLibrary);
        } else {
            // see: 'import.c: _PyImport_FixupExtensionObject'
            PythonModule module = (PythonModule) result;
            module.setAttribute(T___FILE__, spec.path);
            module.setAttribute(T___LIBRARY__, sharedLibrary);
            addLoadedExtensionLibrary(sharedLibrary);

            // add to 'sys.modules'
            PDict sysModules = context.getSysModules();
            sysModules.setItem(spec.name, result);

            // _PyState_AddModule
            Object moduleDef = module.getNativeModuleDef();
            int mIndex = PythonUtils.toIntError(CStructAccess.ReadI64Node.getUncached().read(moduleDef, CFields.PyModuleDef_Base__m_index));
            while (modulesByIndex.size() <= mIndex) {
                modulesByIndex.add(null);
            }
            modulesByIndex.set(mIndex, module);

            // add to 'import.c: extensions'
            extensions.put(Pair.create(spec.path, spec.name), module.getNativeModuleDef());
            return result;
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class GetBuiltin implements TruffleObject {

        @SuppressWarnings("static-method")
        @ExportMessage
        boolean isExecutable() {
            return true;
        }

        @SuppressWarnings("static-method")
        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] arguments) {
            assert arguments.length == 1;
            int id = (int) arguments[0];
            try {
                CApiBuiltinExecutable builtin = PythonCextBuiltinRegistry.builtins[id];
                if (PythonContext.get(null).getCApiContext() != null) {
                    Object llvmLibrary = PythonContext.get(null).getCApiContext().getLLVMLibrary();
                    assert builtin.call() == CApiCallPath.Direct || !isAvailable(builtin, llvmLibrary) : "name clash in builtin vs. CAPI library: " + builtin.name();
                }
                LOGGER.finer("CApiContext.GetBuiltin " + id + " / " + builtin.name());
                return builtin;
            } catch (Throwable e) {
                // this is a fatal error, so print it to stderr:
                e.printStackTrace(new PrintStream(PythonContext.get(null).getEnv().err()));
                throw new RuntimeException(e);
            }
        }

        private static boolean isAvailable(CApiBuiltinExecutable builtin, Object llvmLibrary) {
            if (!InteropLibrary.getUncached().isMemberReadable(llvmLibrary, builtin.name())) {
                return false;
            }
            try {
                InteropLibrary.getUncached().readMember(llvmLibrary, builtin.name());
                return true;
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } catch (UnknownIdentifierException e) {
                // NFI lied to us about symbol availability!
                return false;
            }
        }
    }

    public long getClosurePointer(Object executable) {
        CompilerAsserts.neverPartOfCompilation();
        ClosureInfo info = callableClosureByExecutable.get(executable);
        return info == null ? -1 : info.pointer;
    }

    public Object getClosureDelegate(long pointer) {
        CompilerAsserts.neverPartOfCompilation();
        ClosureInfo info = callableClosures.get(pointer);
        return info == null ? null : info.delegate;
    }

    public void setClosurePointer(Object closure, Object delegate, Object executable, long pointer) {
        CompilerAsserts.neverPartOfCompilation();
        var info = new ClosureInfo(closure, delegate, executable, pointer);
        callableClosureByExecutable.put(executable, info);
        callableClosures.put(pointer, info);
        LOGGER.finer(() -> PythonUtils.formatJString("new NFI closure: (%s, %s) -> %d 0x%x", executable.getClass().getSimpleName(), delegate, pointer, pointer));
    }

    public long registerClosure(String nfiSignature, Object executable, Object delegate) {
        CompilerAsserts.neverPartOfCompilation();
        boolean panama = PythonOptions.UsePanama.getValue(PythonContext.get(null).getEnv().getOptions());
        Object signature = PythonContext.get(null).getEnv().parseInternal(Source.newBuilder(J_NFI_LANGUAGE, (panama ? "with panama " : "") + nfiSignature, "exec").build()).call();
        Object closure = SignatureLibrary.getUncached().createClosure(signature, executable);
        long pointer = PythonUtils.coerceToLong(closure, InteropLibrary.getUncached());
        setClosurePointer(closure, delegate, executable, pointer);
        return pointer;
    }

    /**
     * A cache for the wrappers of type slot methods. The key is a weak reference to the owner class
     * and the value is a table of wrappers. In order to ensure pointer identity, it is important
     * that we use the same wrapper instance as long as the class exists (and the slot wasn't
     * updated). Also, the key's type is {@link PythonManagedClass} such that any
     * {@link PythonBuiltinClassType} must be resolved, and we do not accidentally have two
     * different entries for the same built-in class.
     */
    private final WeakIdentityHashMap<PythonManagedClass, PyProcsWrapper[]> procWrappers = new WeakIdentityHashMap<>();

    @TruffleBoundary
    public Object getOrCreateProcWrapper(PythonManagedClass owner, SlotMethodDef slot, Supplier<PyProcsWrapper> supplier) {
        PyProcsWrapper[] slotWrappers = procWrappers.computeIfAbsent(owner, key -> new PyProcsWrapper[SlotMethodDef.values().length]);
        int idx = slot.ordinal();
        PyProcsWrapper wrapper = slotWrappers[idx];
        if (wrapper == null) {
            wrapper = supplier.get();
            slotWrappers[idx] = wrapper;
        }
        return wrapper;
    }
}
