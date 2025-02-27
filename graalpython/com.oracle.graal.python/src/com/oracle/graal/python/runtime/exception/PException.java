/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.runtime.exception;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.exception.ExceptionNodes;
import com.oracle.graal.python.builtins.objects.exception.PBaseException;
import com.oracle.graal.python.builtins.objects.frame.PFrame;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.traceback.LazyTraceback;
import com.oracle.graal.python.builtins.objects.traceback.MaterializeLazyTracebackNode;
import com.oracle.graal.python.builtins.objects.traceback.PTraceback;
import com.oracle.graal.python.lib.PyExceptionInstanceCheckNode;
import com.oracle.graal.python.nodes.bytecode.PBytecodeRootNode;
import com.oracle.graal.python.nodes.object.BuiltinClassProfiles.IsBuiltinObjectProfile;
import com.oracle.graal.python.nodes.object.BuiltinClassProfiles.IsBuiltinSubtypeObjectProfile;
import com.oracle.graal.python.runtime.GilNode;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Serves both as a throwable carrier of the python exception object and as a represenation of the
 * exception state at a single point in the program. An important invariant is that it must never be
 * rethrown after the contained exception object has been exposed to the program, instead, a new
 * object must be created for each throw.
 */
@ExportLibrary(value = InteropLibrary.class, delegateTo = "pythonException")
public final class PException extends AbstractTruffleException {
    private static final long serialVersionUID = -6437116280384996361L;

    /** A marker object indicating that there is for sure no exception. */
    public static final PException NO_EXCEPTION = new PException(null, null);

    private String message = null;
    final transient Object pythonException;
    private transient PFrame.Reference frameInfo;
    private transient PBytecodeRootNode catchRootNode;
    private int catchBci;
    private transient LazyTraceback traceback;
    private boolean reified = false;
    private boolean skipFirstTracebackFrame;
    private int tracebackFrameCount;

    private PException(Object pythonException, Node node) {
        super(node);
        this.pythonException = pythonException;
    }

    private PException(Object pythonException, Node node, Throwable wrapped) {
        super(null, wrapped, UNLIMITED_STACK_TRACE, node);
        this.pythonException = pythonException;
        assert PyExceptionInstanceCheckNode.executeUncached(pythonException);
    }

    private PException(Object pythonException, LazyTraceback traceback, Throwable wrapped) {
        super(null, wrapped, UNLIMITED_STACK_TRACE, null);
        this.pythonException = pythonException;
        this.traceback = traceback;
        reified = true;
        assert PyExceptionInstanceCheckNode.executeUncached(pythonException);
    }

    public static PException fromObject(Object pythonException, Node node, boolean withJavaStacktrace) {
        Throwable wrapped = null;
        if (withJavaStacktrace) {
            // Create a carrier for the java stacktrace as PException cannot have one
            wrapped = createStacktraceCarrier();
        }
        return fromObject(pythonException, node, wrapped);
    }

    @TruffleBoundary
    private static RuntimeException createStacktraceCarrier() {
        return new RuntimeException();
    }

    /*
     * Note: we use this method to convert a Java StackOverflowError into a Python RecursionError.
     * At the time when this is done, some Java stack frames were already unwinded but there is no
     * guarantee on how many. Therefore, it is important that this method is simple. In particular,
     * do not add calls if that can be avoided.
     */
    public static PException fromObject(Object pythonException, Node node, Throwable wrapped) {
        PException pException = new PException(pythonException, node, wrapped);
        if (pythonException instanceof PBaseException managedException) {
            managedException.setException(pException);
        }
        return pException;
    }

    public static PException fromExceptionInfo(Object pythonException, PTraceback traceback, boolean withJavaStacktrace) {
        LazyTraceback lazyTraceback = null;
        if (traceback != null) {
            lazyTraceback = new LazyTraceback(traceback);
        }
        return fromExceptionInfo(pythonException, lazyTraceback, withJavaStacktrace);
    }

    public static PException fromExceptionInfo(Object pythonException, LazyTraceback traceback, boolean withJavaStacktrace) {
        Throwable wrapped = null;
        if (withJavaStacktrace) {
            // Create a carrier for the java stacktrace as PException cannot have one
            wrapped = createStacktraceCarrier();
        }
        PException pException = new PException(pythonException, traceback, wrapped);
        if (pythonException instanceof PBaseException managedException) {
            managedException.setException(pException);
        }
        return pException;
    }

    @Override
    public String getMessage() {
        if (message == null) {
            message = pythonException.toString();
        }
        return message;
    }

    public void setMessage(Object object) {
        message = object.toString();
    }

    @Override
    public String toString() {
        CompilerAsserts.neverPartOfCompilation();
        if (this == PException.NO_EXCEPTION) {
            return "NO_EXCEPTION";
        }
        return getMessage();
    }

    public int getTracebackStartIndex() {
        return skipFirstTracebackFrame ? 1 : 0;
    }

    public void skipFirstTracebackFrame() {
        this.skipFirstTracebackFrame = true;
        this.tracebackFrameCount = -1;
    }

    public boolean catchingFrameWantedForTraceback() {
        return tracebackFrameCount >= 0 && catchRootNode != null && catchRootNode.frameIsVisibleToPython();
    }

    public PBytecodeRootNode getCatchRootNode() {
        return catchRootNode;
    }

    public int getCatchBci() {
        return catchBci;
    }

    /**
     * Return the associated {@link PBaseException}. This method doesn't ensure traceback
     * consistency and should be avoided unless you can guarantee that the exception will not escape
     * to the program. Use {@link PException#getEscapedException()}.
     */
    public Object getUnreifiedException() {
        return pythonException;
    }

    public void expectUncached(PythonBuiltinClassType error) {
        if (!IsBuiltinObjectProfile.profileObjectUncached(this.getUnreifiedException(), error)) {
            throw this;
        }
    }

    public void expectCached(PythonBuiltinClassType error, IsBuiltinObjectProfile profile) {
        expect(profile, error, profile);
    }

    public void expectIndexError(Node inliningTarget, IsBuiltinObjectProfile profile) {
        if (!profile.profileException(inliningTarget, this, PythonBuiltinClassType.IndexError)) {
            throw this;
        }
    }

    public void expectStopIteration(Node inliningTarget, IsBuiltinObjectProfile profile) {
        if (!profile.profileException(inliningTarget, this, PythonBuiltinClassType.StopIteration)) {
            throw this;
        }
    }

    public void expectAttributeError(Node inliningTarget, IsBuiltinObjectProfile profile) {
        if (!profile.profileException(inliningTarget, this, PythonBuiltinClassType.AttributeError)) {
            throw this;
        }
    }

    public boolean expectTypeOrOverflowError(Node inliningTarget, IsBuiltinObjectProfile profile) {
        boolean ofError = !profile.profileException(inliningTarget, this, PythonBuiltinClassType.TypeError);
        if (ofError && !profile.profileException(inliningTarget, this, PythonBuiltinClassType.OverflowError)) {
            throw this;
        }
        return ofError;
    }

    public void expectOverflowErrorCached(IsBuiltinObjectProfile profile) {
        expectOverflowError(profile, profile);
    }

    public void expectOverflowError(Node inliningTarget, IsBuiltinObjectProfile profile) {
        if (!profile.profileException(inliningTarget, this, PythonBuiltinClassType.OverflowError)) {
            throw this;
        }
    }

    public void expectTypeErrorCached(IsBuiltinObjectProfile profile) {
        expectTypeError(profile, profile);
    }

    public void expectTypeError(Node inliningTarget, IsBuiltinObjectProfile profile) {
        if (!profile.profileException(inliningTarget, this, PythonBuiltinClassType.TypeError)) {
            throw this;
        }
    }

    public void expect(Node inliningTarget, PythonBuiltinClassType error, IsBuiltinObjectProfile profile) {
        if (!profile.profileException(inliningTarget, this, error)) {
            throw this;
        }
    }

    public void expectSubclass(VirtualFrame frame, Node inliningTarget, PythonBuiltinClassType error, IsBuiltinSubtypeObjectProfile profile) {
        if (!profile.profileException(frame, inliningTarget, this, error)) {
            throw this;
        }
    }

    public void setCatchingFrameReference(Frame frame, PBytecodeRootNode catchLocation, int catchBci) {
        this.frameInfo = PArguments.getCurrentFrameInfo(frame);
        this.catchRootNode = catchLocation;
        this.catchBci = catchBci;
    }

    public void markEscaped() {
        markFrameEscaped();
        if (!(pythonException instanceof PBaseException)) {
            materializeNativeExceptionTraceback();
            reified = true;
        }
    }

    private void markFrameEscaped() {
        // Frame may be null when the catch handler is the C boundary, which is internal and
        // shouldn't leak to the traceback
        if (this.frameInfo != null) {
            this.frameInfo.markAsEscaped();
        }
    }

    /**
     * Get the python exception while ensuring that the traceback frame is marked as escaped
     */
    public Object getEscapedException() {
        markEscaped();
        return pythonException;
    }

    /**
     * Get traceback from the time the exception was caught (reified). The contained python object's
     * traceback may not be the same as it is mutable and thus may change after being caught.
     */
    public LazyTraceback getTraceback() {
        ensureReified();
        return traceback;
    }

    /**
     * Set traceback for the exception state. This has no effect on the contained python exception
     * object at this point but it may be synced to the object at a later point if the exception
     * state gets reraised (for example with `raise` without arguments as opposed to the exception
     * object itself being explicitly reraised with `raise e`).
     */
    public void setTraceback(LazyTraceback traceback) {
        ensureReified();
        this.traceback = traceback;
    }

    /**
     * If not done already, create the traceback for this exception state using the frame previously
     * provided to {@link #setCatchingFrameReference(Frame, PBytecodeRootNode, int)} and sync it to
     * the attached python exception.
     */
    public void ensureReified() {
        if (!reified) {
            markFrameEscaped();
            if (pythonException instanceof PBaseException managedException) {
                /*
                 * Make a snapshot of the traceback at the point of the exception handler. This may
                 * be called later than in the exception handler, but only in cases when the
                 * exception hasn't escaped to the program and thus couldn't have changed in the
                 * meantime
                 */
                traceback = managedException.internalReifyException(frameInfo);
            } else {
                materializeNativeExceptionTraceback();
            }
            reified = true;
        }
    }

    public int getTracebackFrameCount() {
        return tracebackFrameCount;
    }

    public void notifyAddedTracebackFrame(boolean visible) {
        if (visible) {
            tracebackFrameCount++;
        }
    }

    /**
     * Prepare a new exception to be thrown to provide the semantics of a reraise. The difference
     * between this method and creating a new exception using
     * {@link #fromObject(Object, Node, boolean) fromObject} is that this method makes the traceback
     * look like the last catch didn't happen, which is desired in `raise` without arguments, at the
     * end of `finally`, `__exit__`...
     */
    public PException getExceptionForReraise(boolean rootNodeVisible) {
        if (pythonException instanceof PBaseException managedException) {
            managedException.setTraceback(getTraceback());
        } else {
            setNativeExceptionTraceback(getTraceback());
        }
        PException pe = PException.fromObject(pythonException, getLocation(), false);
        if (rootNodeVisible) {
            pe.skipFirstTracebackFrame();
        }
        return pe;
    }

    @TruffleBoundary
    private PTraceback setNativeExceptionTraceback(LazyTraceback tb) {
        PTraceback materializedTb = tb != null ? MaterializeLazyTracebackNode.executeUncached(tb) : null;
        ExceptionNodes.SetTracebackNode.executeUncached(pythonException, materializedTb != null ? materializedTb : PNone.NONE);
        return materializedTb;
    }

    @TruffleBoundary
    private void materializeNativeExceptionTraceback() {
        Object existingTraceback = ExceptionNodes.GetTracebackNode.executeUncached(pythonException);
        LazyTraceback nextChain = null;
        if (existingTraceback instanceof PTraceback nextTraceback) {
            nextChain = new LazyTraceback(nextTraceback);
        }
        PTraceback materializedTraceback = setNativeExceptionTraceback(new LazyTraceback(frameInfo, this, nextChain));
        traceback = new LazyTraceback(materializedTraceback);
    }

    @TruffleBoundary
    public void printStack() {
        // a convenience methods for debugging
        ExceptionUtils.printPythonLikeStackTrace(this);
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isException() {
        return true;
    }

    @ExportMessage
    ExceptionType getExceptionType(
                    @CachedLibrary(limit = "1") InteropLibrary lib) throws UnsupportedMessageException {
        return lib.getExceptionType(pythonException);
    }

    @ExportMessage
    RuntimeException throwException(@Exclusive @Cached GilNode gil) {
        boolean mustRelease = gil.acquire();
        try {
            throw getExceptionForReraise(false);
        } finally {
            gil.release(mustRelease);
        }
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasSourceLocation() {
        return getLocation() != null && getLocation().getEncapsulatingSourceSection() != null;
    }

    @ExportMessage(name = "getSourceLocation")
    SourceSection getExceptionSourceLocation(
                    @Bind("$node") Node inliningTarget,
                    @Cached InlinedBranchProfile unsupportedProfile) throws UnsupportedMessageException {
        if (hasSourceLocation()) {
            return getLocation().getEncapsulatingSourceSection();
        }
        unsupportedProfile.enter(inliningTarget);
        throw UnsupportedMessageException.create();
    }

    // Note: remaining interop messages are forwarded to the contained PBaseException
}
