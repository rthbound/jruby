/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.instrument.Probe;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.tools.CoverageTracker;

import jnr.ffi.LibraryLoader;
import jnr.ffi.Runtime;
import jnr.ffi.provider.MemoryManager;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyNil;
import org.jruby.TruffleContextInterface;
import org.jruby.ext.ffi.Platform;
import org.jruby.ext.ffi.Platform.OS_TYPE;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.RubyRootNode;
import org.jruby.truffle.nodes.control.SequenceNode;
import org.jruby.truffle.nodes.core.BindingNodes;
import org.jruby.truffle.nodes.core.LoadRequiredLibrariesNode;
import org.jruby.truffle.nodes.core.SetTopLevelBindingNode;
import org.jruby.truffle.nodes.dispatch.CallDispatchHeadNode;
import org.jruby.truffle.nodes.exceptions.TopLevelRaiseHandler;
import org.jruby.truffle.nodes.instrument.RubyDefaultASTProber;
import org.jruby.truffle.nodes.methods.DeclarationContext;
import org.jruby.truffle.nodes.rubinius.RubiniusPrimitiveManager;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.core.ArrayOperations;
import org.jruby.truffle.runtime.core.CoreLibrary;
import org.jruby.truffle.runtime.core.StringOperations;
import org.jruby.truffle.runtime.core.SymbolTable;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.loader.FeatureLoader;
import org.jruby.truffle.runtime.loader.SourceCache;
import org.jruby.truffle.runtime.loader.SourceLoader;
import org.jruby.truffle.runtime.methods.InternalMethod;
import org.jruby.truffle.runtime.object.ObjectIDOperations;
import org.jruby.truffle.runtime.rubinius.RubiniusConfiguration;
import org.jruby.truffle.runtime.sockets.NativeSockets;
import org.jruby.truffle.runtime.subsystems.*;
import org.jruby.truffle.translator.TranslatorDriver;
import org.jruby.truffle.translator.TranslatorDriver.ParserContext;
import org.jruby.util.ByteList;
import org.jruby.util.StringSupport;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The global state of a running Ruby system.
 */
public class RubyContext extends ExecutionContext implements TruffleContextInterface {

    private static volatile RubyContext latestInstance;

    private final Ruby runtime;

    private final Options options;

    private final POSIX posix;
    private final NativeSockets nativeSockets;
    private final MemoryManager memoryManager = Runtime.getSystemRuntime().getMemoryManager();

    private final CoreLibrary coreLibrary;
    private final FeatureLoader featureLoader;
    private final TraceManager traceManager;
    private final ObjectSpaceManager objectSpaceManager;
    private final ThreadManager threadManager;
    private final AtExitManager atExitManager;
    private final SymbolTable symbolTable = new SymbolTable(this);
    private final Warnings warnings;
    private final SafepointManager safepointManager;
    private final LexicalScope rootLexicalScope;
    private final CompilerOptions compilerOptions;
    private final RubiniusPrimitiveManager rubiniusPrimitiveManager;
    private final CoverageTracker coverageTracker;
    private final InstrumentationServerManager instrumentationServerManager;
    private final AttachmentsManager attachmentsManager;
    private final SourceCache sourceCache;
    private final RubiniusConfiguration rubiniusConfiguration;

    private final AtomicLong nextObjectID = new AtomicLong(ObjectIDOperations.FIRST_OBJECT_ID);

    private final boolean runningOnWindows;

    private final PrintStream debugStandardOut;

    public RubyContext(Ruby runtime) {
        options = new Options();

        latestInstance = this;

        assert runtime != null;

        compilerOptions = Truffle.getRuntime().createCompilerOptions();

        if (compilerOptions.supportsOption("MinTimeThreshold")) {
            compilerOptions.setOption("MinTimeThreshold", 100000000);
        }

        if (compilerOptions.supportsOption("MinInliningMaxCallerSize")) {
            compilerOptions.setOption("MinInliningMaxCallerSize", 5000);
        }

        // TODO CS 28-Feb-15 this is global
        Probe.registerASTProber(new RubyDefaultASTProber());

        // TODO(CS, 28-Jan-15) this is global
        // TODO(CS, 28-Jan-15) maybe not do this for core?
        if (options.COVERAGE) {
            coverageTracker = new CoverageTracker();
        } else {
            coverageTracker = null;
        }

        safepointManager = new SafepointManager(this);

        this.runtime = runtime;

        // JRuby+Truffle uses POSIX for all IO - we need the native version
        posix = POSIXFactory.getNativePOSIX(new TrufflePOSIXHandler(this));

        final LibraryLoader<NativeSockets> loader = LibraryLoader.create(NativeSockets.class);
        loader.library("c");
        nativeSockets = loader.load();

        warnings = new Warnings(this);

        // Object space manager needs to come early before we create any objects
        objectSpaceManager = new ObjectSpaceManager(this);

        coreLibrary = new CoreLibrary(this);
        rootLexicalScope = new LexicalScope(null, coreLibrary.getObjectClass());
        coreLibrary.initialize();

        featureLoader = new FeatureLoader(this);
        traceManager = new TraceManager();
        atExitManager = new AtExitManager(this);

        threadManager = new ThreadManager(this);
        threadManager.initialize();


        rubiniusPrimitiveManager = new RubiniusPrimitiveManager();
        rubiniusPrimitiveManager.addAnnotatedPrimitives();

        if (options.INSTRUMENTATION_SERVER_PORT != 0) {
            instrumentationServerManager = new InstrumentationServerManager(this, options.INSTRUMENTATION_SERVER_PORT);
            instrumentationServerManager.start();
        } else {
            instrumentationServerManager = null;
        }

        runningOnWindows = Platform.getPlatform().getOS() == OS_TYPE.WINDOWS;

        attachmentsManager = new AttachmentsManager(this);
        sourceCache = new SourceCache(new SourceLoader(this));
        rubiniusConfiguration = RubiniusConfiguration.create(this);

        final PrintStream configStandardOut = runtime.getInstanceConfig().getOutput();
        debugStandardOut = (configStandardOut == System.out) ? null : configStandardOut;
    }

    public Object send(Object object, String methodName, DynamicObject block, Object... arguments) {
        CompilerAsserts.neverPartOfCompilation();

        assert block == null || RubyGuards.isRubyProc(block);

        final InternalMethod method = ModuleOperations.lookupMethod(coreLibrary.getMetaClass(object), methodName);

        if (method == null || method.isUndefined()) {
            return null;
        }

        return method.getCallTarget().call(
                RubyArguments.pack(method, method.getDeclarationFrame(), null, object, block, DeclarationContext.METHOD, arguments));
    }

    /* For debugging in Java. */
    public static Object debugEval(String code) {
        CompilerAsserts.neverPartOfCompilation();
        final FrameInstance currentFrameInstance = Truffle.getRuntime().getCurrentFrame();
        final Frame currentFrame = currentFrameInstance.getFrame(FrameAccess.MATERIALIZE, true);
        return getLatestInstance().inlineRubyHelper(null, currentFrame, code);
    }

    @TruffleBoundary
    public Object inlineRubyHelper(Node currentNode, String expression, Object... arguments) {
        return inlineRubyHelper(currentNode, Truffle.getRuntime().getCurrentFrame().getFrame(FrameAccess.MATERIALIZE, true), expression, arguments);
    }

    public Object inlineRubyHelper(Node currentNode, Frame frame, String expression, Object... arguments) {
        final MaterializedFrame evalFrame = setupInlineRubyFrame(frame, arguments);
        final DynamicObject binding = BindingNodes.createBinding(this, evalFrame);
        return eval(ParserContext.INLINE, ByteList.create(expression), binding, true, "inline-ruby", currentNode);
    }

    private MaterializedFrame setupInlineRubyFrame(Frame frame, Object... arguments) {
        CompilerDirectives.transferToInterpreter();
        final MaterializedFrame evalFrame = Truffle.getRuntime().createMaterializedFrame(
                RubyArguments.pack(
                        RubyArguments.getMethod(frame.getArguments()),
                        null,
                        null,
                        RubyArguments.getSelf(frame.getArguments()),
                        null,
                        DeclarationContext.INSTANCE_EVAL,
                        new Object[] {}),
                new FrameDescriptor(frame.getFrameDescriptor().getDefaultValue()));

        if (arguments.length % 2 == 1) {
            throw new UnsupportedOperationException("odd number of name-value pairs for arguments");
        }

        for (int n = 0; n < arguments.length; n += 2) {
            evalFrame.setObject(evalFrame.getFrameDescriptor().findOrAddFrameSlot(arguments[n]), arguments[n + 1]);
        }

        return evalFrame;
    }

    @Override
    public void initialize() {
        // Give the core library manager a chance to tweak some of those methods

        coreLibrary.initializeAfterMethodsAdded();

        // Set program arguments

        for (IRubyObject arg : ((org.jruby.RubyArray) runtime.getObject().getConstant("ARGV")).toJavaArray()) {
            assert arg != null;

            ArrayOperations.append(coreLibrary.getArgv(), Layouts.STRING.createString(Layouts.CLASS.getInstanceFactory(coreLibrary.getStringClass()), StringOperations.encodeByteList(arg.toString(), UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null));
        }

        // Set the load path

        DynamicObject receiver = coreLibrary.getGlobalVariablesObject();
        final DynamicObject loadPath = (DynamicObject) receiver.get("$:", coreLibrary.getNilObject());

        for (IRubyObject path : ((org.jruby.RubyArray) runtime.getLoadService().getLoadPath()).toJavaArray()) {
            String pathString = path.toString();

            if (!(pathString.endsWith("lib/ruby/2.2/site_ruby")
                    || pathString.endsWith("lib/ruby/shared")
                    || pathString.endsWith("lib/ruby/stdlib"))) {

                if (pathString.startsWith("uri:classloader:")) {
                    pathString = SourceLoader.JRUBY_SCHEME + pathString.substring("uri:classloader:".length());
                }

                ArrayOperations.append(loadPath, Layouts.STRING.createString(coreLibrary.getStringFactory(), StringOperations.encodeByteList(pathString, UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null));
            }
        }

        // Load our own stdlib path

        String home = runtime.getInstanceConfig().getJRubyHome();

        if (home.startsWith("uri:classloader:")) {
            home = home.substring("uri:classloader:".length());

            while (home.startsWith("/")) {
                home = home.substring(1);
            }

            home = SourceLoader.JRUBY_SCHEME + "/" + home;
        }

        home = home + "/";

        // Libraries copied unmodified from MRI
        ArrayOperations.append(loadPath, Layouts.STRING.createString(coreLibrary.getStringFactory(), StringOperations.encodeByteList(home + "lib/ruby/truffle/mri", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null));

        // Our own implementations
        ArrayOperations.append(loadPath, Layouts.STRING.createString(coreLibrary.getStringFactory(), StringOperations.encodeByteList(home + "lib/ruby/truffle/truffle", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null));

        // Libraries from RubySL
        for (String lib : Arrays.asList("rubysl-strscan", "rubysl-stringio",
                "rubysl-complex", "rubysl-date", "rubysl-pathname",
                "rubysl-tempfile", "rubysl-socket", "rubysl-securerandom",
                "rubysl-timeout", "rubysl-webrick")) {
            ArrayOperations.append(loadPath, Layouts.STRING.createString(coreLibrary.getStringFactory(), StringOperations.encodeByteList(home + "lib/ruby/truffle/rubysl/" + lib + "/lib", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null));
        }

        // Shims
        ArrayOperations.append(loadPath, Layouts.STRING.createString(coreLibrary.getStringFactory(), StringOperations.encodeByteList(home + "lib/ruby/truffle/shims", UTF8Encoding.INSTANCE), StringSupport.CR_UNKNOWN, null));
    }

    public static String checkInstanceVariableName(RubyContext context, String name, Node currentNode) {
        if (!name.startsWith("@")) {
            throw new RaiseException(context.getCoreLibrary().nameErrorInstanceNameNotAllowable(name, currentNode));
        }

        return name;
    }

    public static String checkClassVariableName(RubyContext context, String name, Node currentNode) {
        if (!name.startsWith("@@")) {
            throw new RaiseException(context.getCoreLibrary().nameErrorInstanceNameNotAllowable(name, currentNode));
        }

        return name;
    }

    public boolean isRunningOnWindows() {
        return runningOnWindows;
    }

    public void loadFile(String fileName, Node currentNode) throws IOException {
        if (new File(fileName).isAbsolute() || fileName.startsWith(SourceLoader.JRUBY_SCHEME) || fileName.startsWith(SourceLoader.TRUFFLE_SCHEME)) {
            loadFileAbsolute(fileName, currentNode);
        } else {
            loadFileAbsolute(new File(this.getRuntime().getCurrentDirectory() + File.separator + fileName).getCanonicalPath(), currentNode);
        }
    }

    private void loadFileAbsolute(String fileName, Node currentNode) throws IOException {
        final Source source = sourceCache.getSource(fileName);
        load(source, currentNode);
    }

    public void load(Source source, Node currentNode) {
        parseAndExecute(source, UTF8Encoding.INSTANCE, ParserContext.TOP_LEVEL, coreLibrary.getMainObject(), null, true, DeclarationContext.TOP_LEVEL, currentNode);
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public DynamicObject getSymbol(String name) {
        return symbolTable.getSymbol(name);
    }

    public DynamicObject getSymbol(ByteList name) {
        return symbolTable.getSymbol(name);
    }

    @TruffleBoundary
    public Object instanceEval(ByteList code, Object self, String filename, Node currentNode) {
        final Source source = Source.fromText(code, filename);
        return parseAndExecute(source, code.getEncoding(), ParserContext.EVAL, self, null, true, DeclarationContext.INSTANCE_EVAL, currentNode);
    }

    @TruffleBoundary
    public Object eval(ParserContext parserContext, ByteList code, DynamicObject binding, boolean ownScopeForAssignments, String filename, Node currentNode) {
        assert RubyGuards.isRubyBinding(binding);
        final Source source = Source.fromText(code, filename);
        final MaterializedFrame frame = Layouts.BINDING.getFrame(binding);
        final DeclarationContext declarationContext = RubyArguments.getDeclarationContext(frame.getArguments());
        return parseAndExecute(source, code.getEncoding(), parserContext, RubyArguments.getSelf(frame.getArguments()), frame, ownScopeForAssignments, declarationContext, currentNode);
    }

    @TruffleBoundary
    public Object parseAndExecute(Source source, Encoding defaultEncoding, ParserContext parserContext, Object self, MaterializedFrame parentFrame, boolean ownScopeForAssignments,
            DeclarationContext declarationContext, Node currentNode) {
        final RubyRootNode rootNode = parse(source, defaultEncoding, parserContext, parentFrame, ownScopeForAssignments, currentNode);
        return execute(parserContext, declarationContext, rootNode, parentFrame, self);
    }

    @TruffleBoundary
    private RubyRootNode parse(Source source, Encoding defaultEncoding, ParserContext parserContext, MaterializedFrame parentFrame, boolean ownScopeForAssignments, Node currentNode) {
        final TranslatorDriver translator = new TranslatorDriver(this);
        return translator.parse(this, source, defaultEncoding, parserContext, parentFrame, ownScopeForAssignments, currentNode);
    }

    @TruffleBoundary
    private Object execute(ParserContext parserContext, DeclarationContext declarationContext, RubyRootNode rootNode, MaterializedFrame parentFrame, Object self) {
        final CallTarget callTarget = Truffle.getRuntime().createCallTarget(rootNode);

        final DynamicObject declaringModule;
        if (parserContext == ParserContext.EVAL && parentFrame != null) {
            declaringModule = RubyArguments.getMethod(parentFrame.getArguments()).getDeclaringModule();
        } else if (parserContext == ParserContext.MODULE) {
            assert RubyGuards.isRubyModule(self);
            declaringModule = (DynamicObject) self;
        } else {
            declaringModule = getCoreLibrary().getObjectClass();
        }

        final InternalMethod method = new InternalMethod(rootNode.getSharedMethodInfo(), rootNode.getSharedMethodInfo().getName(),
                declaringModule, Visibility.PUBLIC, false, callTarget, parentFrame);

        return callTarget.call(RubyArguments.pack(method, parentFrame, null, self, null, declarationContext, new Object[] {}));
    }

    public long getNextObjectID() {
        final long id = nextObjectID.getAndAdd(2);

        if (id < 0) {
            CompilerDirectives.transferToInterpreter();
            nextObjectID.set(Long.MIN_VALUE);
            throw new RuntimeException("Object IDs exhausted");
        }

        return id;
    }

    public void innerShutdown(boolean normalExit) {
        atExitManager.run(normalExit);

        if (instrumentationServerManager != null) {
            instrumentationServerManager.shutdown();
        }

        threadManager.shutdown();
    }

    public Object makeTuple(VirtualFrame frame, CallDispatchHeadNode newTupleNode, Object... values) {
        return newTupleNode.call(frame, getCoreLibrary().getTupleClass(), "create", null, values);
    }

    public IRubyObject toJRuby(Object object) {
        if (object == getCoreLibrary().getNilObject()) {
            return runtime.getNil();
        } else if (object == getCoreLibrary().getKernelModule()) {
            return runtime.getKernel();
        } else if (object == getCoreLibrary().getMainObject()) {
            return runtime.getTopSelf();
        } else if (object instanceof Boolean) {
            return runtime.newBoolean((boolean) object);
        } else if (object instanceof Integer) {
            return runtime.newFixnum((int) object);
        } else if (object instanceof Long) {
            return runtime.newFixnum((long) object);
        } else if (object instanceof Double) {
            return runtime.newFloat((double) object);
        } else if (RubyGuards.isRubyString(object)) {
            return toJRubyString((DynamicObject) object);
        } else if (RubyGuards.isRubyArray(object)) {
            return toJRubyArray((DynamicObject) object);
        } else if (RubyGuards.isRubyEncoding(object)) {
            return toJRubyEncoding((DynamicObject) object);
        } else {
            throw getRuntime().newRuntimeError("cannot pass " + object + " (" + object.getClass().getName()  + ") to JRuby");
        }
    }

    public IRubyObject[] toJRuby(Object... objects) {
        final IRubyObject[] store = new IRubyObject[objects.length];

        for (int n = 0; n < objects.length; n++) {
            store[n] = toJRuby(objects[n]);
        }

        return store;
    }

    public org.jruby.RubyArray toJRubyArray(DynamicObject array) {
        assert RubyGuards.isRubyArray(array);
        return runtime.newArray(toJRuby(ArrayOperations.toObjectArray(array)));
    }

    public IRubyObject toJRubyEncoding(DynamicObject encoding) {
        assert RubyGuards.isRubyEncoding(encoding);
        return runtime.getEncodingService().rubyEncodingFromObject(runtime.newString(Layouts.ENCODING.getName(encoding)));
    }

    public org.jruby.RubyString toJRubyString(DynamicObject string) {
        assert RubyGuards.isRubyString(string);

        final org.jruby.RubyString jrubyString = runtime.newString(StringOperations.getByteList(string).dup());

        final Object tainted = string.get(Layouts.TAINTED_IDENTIFIER, coreLibrary.getNilObject());

        if (tainted instanceof Boolean && (boolean) tainted) {
            jrubyString.setTaint(true);
        }

        return jrubyString;
    }

    public Object toTruffle(IRubyObject object) {
        if (object == runtime.getTopSelf()) {
            return getCoreLibrary().getMainObject();
        } else if (object == runtime.getKernel()) {
            return getCoreLibrary().getKernelModule();
        } else if (object instanceof RubyNil) {
            return getCoreLibrary().getNilObject();
        } else if (object instanceof org.jruby.RubyBoolean.True) {
            return true;
        } else if (object instanceof org.jruby.RubyBoolean.False) {
            return false;
        } else if (object instanceof org.jruby.RubyFixnum) {
            final long value = ((org.jruby.RubyFixnum) object).getLongValue();

            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                return value;
            }

            return (int) value;
        } else if (object instanceof org.jruby.RubyFloat) {
            return ((org.jruby.RubyFloat) object).getDoubleValue();
        } else if (object instanceof org.jruby.RubyBignum) {
            final BigInteger value = ((org.jruby.RubyBignum) object).getBigIntegerValue();
            return Layouts.BIGNUM.createBignum(coreLibrary.getBignumFactory(), value);
        } else if (object instanceof org.jruby.RubyString) {
            return toTruffle((org.jruby.RubyString) object);
        } else if (object instanceof org.jruby.RubySymbol) {
            return getSymbolTable().getSymbol(object.toString());
        } else if (object instanceof org.jruby.RubyArray) {
            return toTruffle((org.jruby.RubyArray) object);
        } else if (object instanceof org.jruby.RubyException) {
            return toTruffle((org.jruby.RubyException) object, null);
        } else {
            throw object.getRuntime().newRuntimeError("cannot pass " + object.inspect() + " (" + object.getClass().getName()  + ") to Truffle");
        }
    }

    public DynamicObject toTruffle(org.jruby.RubyArray array) {
        final Object[] store = new Object[array.size()];

        for (int n = 0; n < store.length; n++) {
            store[n] = toTruffle(array.entry(n));
        }

        return Layouts.ARRAY.createArray(coreLibrary.getArrayFactory(), store, store.length);
    }

    public DynamicObject toTruffle(org.jruby.RubyString jrubyString) {
        final DynamicObject truffleString = Layouts.STRING.createString(coreLibrary.getStringFactory(), jrubyString.getByteList().dup(), StringSupport.CR_UNKNOWN, null);

        if (jrubyString.isTaint()) {
            truffleString.define(Layouts.TAINTED_IDENTIFIER, true, 0);
        }

        return truffleString;
    }

    public DynamicObject toTruffle(org.jruby.RubyException jrubyException, RubyNode currentNode) {
        switch (jrubyException.getMetaClass().getName()) {
            case "ArgumentError":
                return getCoreLibrary().argumentError(jrubyException.getMessage().toString(), currentNode);
            case "Encoding::CompatibilityError":
                return getCoreLibrary().encodingCompatibilityError(jrubyException.getMessage().toString(), currentNode);
            case "TypeError":
                return getCoreLibrary().typeError(jrubyException.getMessage().toString(), currentNode);
            case "RegexpError":
                return getCoreLibrary().regexpError(jrubyException.getMessage().toString(), currentNode);
        }

        throw new UnsupportedOperationException("Don't know how to translate " + jrubyException.getMetaClass().getName());
    }

    public Ruby getRuntime() {
        return runtime;
    }

    public CoreLibrary getCoreLibrary() {
        return coreLibrary;
    }

    public FeatureLoader getFeatureLoader() {
        return featureLoader;
    }

    public ObjectSpaceManager getObjectSpaceManager() {
        return objectSpaceManager;
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public AtExitManager getAtExitManager() {
        return atExitManager;
    }

    @Override
    public String getLanguageShortName() {
        return "ruby";
    }

    public TraceManager getTraceManager() {
        return traceManager;
    }

    public Warnings getWarnings() {
        return warnings;
    }

    public SafepointManager getSafepointManager() {
        return safepointManager;
    }

    public Random getRandom() {
        return ThreadLocalRandom.current();
    }

    public LexicalScope getRootLexicalScope() {
        return rootLexicalScope;
    }

    public CompilerOptions getCompilerOptions() {
        return compilerOptions;
    }

    public RubiniusPrimitiveManager getRubiniusPrimitiveManager() {
        return rubiniusPrimitiveManager;
    }

    // TODO(mg): we need to find a better place for this:
    private TruffleObject multilanguageObject;

    public TruffleObject getMultilanguageObject() {
        return multilanguageObject;
    }

    public void setMultilanguageObject(TruffleObject multilanguageObject) {
        this.multilanguageObject = multilanguageObject;
    }

    public CoverageTracker getCoverageTracker() {
        return coverageTracker;
    }

    public static RubyContext getLatestInstance() {
        return latestInstance;
    }

    public AttachmentsManager getAttachmentsManager() {
        return attachmentsManager;
    }

    public SourceCache getSourceCache() {
        return sourceCache;
    }

    public RubiniusConfiguration getRubiniusConfiguration() {
        return rubiniusConfiguration;
    }

    public POSIX getPosix() {
        return posix;
    }

    public NativeSockets getNativeSockets() {
        return nativeSockets;
    }

    @Override
    public Object execute(final org.jruby.ast.RootNode rootNode) {
        coreLibrary.getGlobalVariablesObject().define("$0", toTruffle(runtime.getGlobalVariables().get("$0")), 0);

        String inputFile = rootNode.getPosition().getFile();
        final Source source;
        try {
            if (!inputFile.equals("-e")) {
                inputFile = new File(inputFile).getCanonicalPath();
            }
            source = sourceCache.getSource(inputFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        featureLoader.setMainScriptSource(source);

        final RubyRootNode originalRootNode = parse(source, UTF8Encoding.INSTANCE, ParserContext.TOP_LEVEL, null, true, null);

        final SourceSection sourceSection = originalRootNode.getSourceSection();
        final RubyNode wrappedBody =
                new TopLevelRaiseHandler(this, sourceSection,
                        SequenceNode.sequence(this, sourceSection,
                                new SetTopLevelBindingNode(this, sourceSection),
                                new LoadRequiredLibrariesNode(this, sourceSection),
                                originalRootNode.getBody()));

        final RubyRootNode newRootNode = originalRootNode.withBody(wrappedBody);

        return execute(ParserContext.TOP_LEVEL, DeclarationContext.TOP_LEVEL, newRootNode, null, coreLibrary.getMainObject());
    }

    @Override
    public void shutdown() {
        innerShutdown(true);
    }

    public PrintStream getDebugStandardOut() {
        return debugStandardOut;
    }

    public Options getOptions() {
        return options;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }
}
