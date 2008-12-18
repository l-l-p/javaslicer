package de.unisb.cs.st.javaslicer.tracer;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.ByteOrder;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceMethodVisitor;

import de.hammacher.util.ConcurrentReferenceHashMap;
import de.hammacher.util.MultiplexedFileWriter;
import de.hammacher.util.SimpleArrayList;
import de.hammacher.util.StringCacheOutput;
import de.hammacher.util.ConcurrentReferenceHashMap.Option;
import de.hammacher.util.ConcurrentReferenceHashMap.ReferenceType;
import de.hammacher.util.MultiplexedFileWriter.MultiplexOutputStream;
import de.unisb.cs.st.javaslicer.common.TraceSequenceTypes;
import de.unisb.cs.st.javaslicer.common.classRepresentation.ReadClass;
import de.unisb.cs.st.javaslicer.common.classRepresentation.instructions.AbstractInstruction;
import de.unisb.cs.st.javaslicer.common.exceptions.TracerException;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.IdentifiableInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.JSRInliner;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.PauseTracingInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.ThreadInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.TracingClassInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.instrumenter.TracingMethodInstrumenter;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.ObjectIdentifier;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequenceFactory;

public class Tracer implements ClassFileTransformer {

    /**
     * The asm {@link ClassWriter} has an "error" (maybe feature) in the
     * method {@link #getCommonSuperClass(String, String)}, because it
     * only uses the classloader of the current class, not the system
     * class loader.
     *
     * @author Clemens Hammacher
     */
    private static final class FixedClassWriter extends ClassWriter {
        protected FixedClassWriter(final int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(final String type1, final String type2)
        {
            Class<?> c, d;
            try {
                c = Class.forName(type1.replace('/', '.'));
            } catch (final ClassNotFoundException e) {
                try {
                    c = ClassLoader.getSystemClassLoader().loadClass(type1.replace('/', '.'));
                } catch (final ClassNotFoundException e1) {
                    throw new RuntimeException(e1);
                }
            }
            try {
                d = Class.forName(type2.replace('/', '.'));
            } catch (final ClassNotFoundException e) {
                try {
                    d = ClassLoader.getSystemClassLoader().loadClass(type2.replace('/', '.'));
                } catch (final ClassNotFoundException e1) {
                    throw new RuntimeException(e1);
                }
            }
            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            }
            do {
                c = c.getSuperclass();
            } while (!c.isAssignableFrom(d));
            return c.getName().replace('.', '/');
        }
    }

    private static final long serialVersionUID = 3853368930402145734L;

    private static Tracer instance = null;

    public final boolean debug;
    public final boolean check;

    private final String[] pauseTracingClasses = new String[] {
            "java.lang.ClassLoader",
            "sun.instrument.InstrumentationImpl"
    };
    // there are classes needed while retransforming.
    // these must be loaded a-priori, otherwise circular dependencies may occur
    private final String[] classesToPreload = {
            "java.io.IOException",
            "java.io.EOFException",
            NullThreadTracer.class.getName(),
    };


    protected final TraceSequenceFactory seqFactory;

    private final Map<Thread, ThreadTracer> threadTracers;

    // an (untraced) list that holds ThreadTracers that can be finished. They are added to this
    // list first, because if they would be finished immediately, it would leed to an recursive
    // loop...
    protected SimpleArrayList<TracingThreadTracer> readyThreadTracers = new SimpleArrayList<TracingThreadTracer>();

    protected final List<TraceSequenceTypes.Type> traceSequenceTypes
        = Collections.synchronizedList(new SimpleArrayList<TraceSequenceTypes.Type>());

    public volatile boolean tracingStarted = false;
    public volatile boolean tracingReady = false;

    private final MultiplexedFileWriter file;
    private final ConcurrentLinkedQueue<ReadClass> readClasses = new ConcurrentLinkedQueue<ReadClass>();
    private final StringCacheOutput readClassesStringCache = new StringCacheOutput();
    private final DataOutputStream readClassesOutputStream;
    private final DataOutputStream threadTracersOutputStream;

    private Set<String> notRedefinedClasses;

    private final ConcurrentMap<ThreadTracer, CountDownLatch> writtenThreadTracers =
        new ConcurrentReferenceHashMap<ThreadTracer, CountDownLatch>(
            32, .75f, 16, ReferenceType.WEAK, ReferenceType.STRONG,
            EnumSet.of(Option.IDENTITY_COMPARISONS));

    // the thread that just creates a threadTracer (needed to avoid stack overflowes)
    private Thread threadTracerBeingCreated = null;

    private final AtomicInteger errorCount = new AtomicInteger(0);

    private static final boolean COMPUTE_FRAMES = false;
    private String lastErrorString;

    private final Instrumentation instrumentation;

    private final AtomicLong totalTransformationTime = new AtomicLong(0);
    private final AtomicInteger totalTransformedClasses = new AtomicInteger(0);


    private Tracer(final File filename, final boolean debug, final boolean check,
            final TraceSequenceFactory seqFac, final Instrumentation instrumentation) throws IOException {
        this.debug = debug;
        this.check = check;
        this.seqFactory = seqFac;
        this.instrumentation = instrumentation;
        this.file = new MultiplexedFileWriter(filename, 512, MultiplexedFileWriter.is64bitVM,
                ByteOrder.nativeOrder(), seqFac.shouldAutoFlushFile());
        this.file.setReuseStreamIds(true);
        final MultiplexOutputStream readClassesMultiplexedStream = this.file.newOutputStream();
        if (readClassesMultiplexedStream.getId() != 0)
            throw new AssertionError("MultiplexedFileWriter does not initially return stream id 0");
        this.readClassesOutputStream = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(readClassesMultiplexedStream, 512), 512));
        final MultiplexOutputStream threadTracersMultiplexedStream = this.file.newOutputStream();
        if (threadTracersMultiplexedStream.getId() != 1)
            throw new AssertionError("MultiplexedFileWriter does not monotonously increase stream ids");
        this.threadTracersOutputStream = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(threadTracersMultiplexedStream, 512), 512));
        final ConcurrentReferenceHashMap<Thread, ThreadTracer> threadTracersMap =
            new ConcurrentReferenceHashMap<Thread, ThreadTracer>(
                    32, .75f, 16, ReferenceType.WEAK, ReferenceType.STRONG,
                    EnumSet.of(Option.IDENTITY_COMPARISONS));
        threadTracersMap.addRemoveStaleListener(new ConcurrentReferenceHashMap.RemoveStaleListener<ThreadTracer>() {
            public void removed(final ThreadTracer removedValue) {
                if (removedValue instanceof TracingThreadTracer) {
                    synchronized (Tracer.this.readyThreadTracers) {
                        Tracer.this.readyThreadTracers.add((TracingThreadTracer) removedValue);
                    }
                }
            }
        });
        this.threadTracers = threadTracersMap;
    }

    public void error(final Exception e) {
        if (this.debug) {
            final StringWriter sw = new StringWriter();
            final PrintWriter ps = new PrintWriter(sw);
            e.printStackTrace(ps);
            System.err.println(this.lastErrorString = sw.toString());
        } else {
            System.err.println(this.lastErrorString = e.toString());
        }
        this.errorCount.getAndIncrement();
    }

    public static void newInstance(final File filename, final boolean debug, final boolean check,
            final TraceSequenceFactory seqFac, final Instrumentation instrumentation) throws IOException {
        if (instance != null)
            throw new IllegalStateException("Tracer instance already exists");
        instance = new Tracer(filename, debug, check, seqFac, instrumentation);
    }

    public static Tracer getInstance() {
        if (instance == null)
            throw new IllegalStateException("Tracer instance not created");
        return instance;
    }

    public void add(final Instrumentation inst, final boolean retransformClasses) throws TracerException {

        // check the JRE version we run on
        final String javaVersion = System.getProperty("java.version");
        final int secondPointPos = javaVersion.indexOf('.', javaVersion.indexOf('.')+1);
        try {
            if (secondPointPos != -1) {
                final double javaVersionDouble = Double.valueOf(javaVersion.substring(0, secondPointPos));
                if (javaVersionDouble < 1.59) {
                    System.err.println("This tracer requires JRE >= 1.6, you are running " + javaVersion + ".");
                    System.exit(-1);
                }
            }
        } catch (final NumberFormatException e) {
            // ignore (no check...)
        }

        if (retransformClasses && !inst.isRetransformClassesSupported())
            throw new TracerException("Your JVM does not support retransformation of classes");

        final List<Class<?>> additionalClassesToRetransform = new ArrayList<Class<?>>();
        for (final String classname: this.classesToPreload) {
            Class<?> class1;
            try {
                class1 = ClassLoader.getSystemClassLoader().loadClass(classname);
            } catch (final ClassNotFoundException e) {
                continue;
            }
            additionalClassesToRetransform.add(class1);
        }

        // call a method in ObjectIdentifier to ensure that the class is initialized
        ObjectIdentifier.instance.getObjectId(this);

        this.notRedefinedClasses = new HashSet<String>();
        for (final Class<?> class1: additionalClassesToRetransform)
            this.notRedefinedClasses.add(class1.getName());
        for (final Class<?> class1: inst.getAllLoadedClasses())
            this.notRedefinedClasses.add(class1.getName());

        inst.addTransformer(this, true);

        if (retransformClasses) {
            final ArrayList<Class<?>> classesToRetransform = new ArrayList<Class<?>>();
            for (final Class<?> class1: inst.getAllLoadedClasses()) {
                final boolean isModifiable = inst.isModifiableClass(class1);
                if (this.debug && !isModifiable && !class1.isPrimitive() && !class1.isArray())
                    System.out.println("not modifiable: " + class1);
                boolean modify = isModifiable && !class1.isInterface();
                modify &= !class1.getName().startsWith("de.unisb.cs.st.javaslicer.tracer");
                if (modify)
                    classesToRetransform.add(class1);
            }
            for (final Class<?> class1: additionalClassesToRetransform) {
                final boolean isModifiable = inst.isModifiableClass(class1);
                if (this.debug && !isModifiable && !class1.isPrimitive() && !class1.isArray())
                    System.out.println("not modifiable: " + class1);
                boolean modify = isModifiable && !class1.isInterface();
                modify &= !class1.getName().startsWith("de.unisb.cs.st.javaslicer.tracer");
                if (modify && !classesToRetransform.contains(class1)) {
                    classesToRetransform.add(class1);
                }
            }

            if (this.debug) {
                System.out.println("classes to retransform (" + classesToRetransform.size() + "):");
                for (final Class<?> c1 : classesToRetransform) {
                    System.out.println(c1);
                }
                System.out.println("############################################");
            }

            try {
                inst.retransformClasses(classesToRetransform.toArray(new Class<?>[classesToRetransform.size()]));
                if (this.debug)
                    System.out.println("Initial instrumentation ready");
            } catch (final UnmodifiableClassException e) {
                throw new TracerException(e);
            }

            if (this.debug) {
                // print statistics once now and once when all finished (in finish() method)
                TracingMethodInstrumenter.printStats(System.out);
            }
        }

        synchronized (this.threadTracers) {
            this.tracingStarted = true;
            if (!this.tracingReady) {
                for (final ThreadTracer tt: this.threadTracers.values())
                    tt.unpauseTracing();
            }
        }
    }

    private final Object transformationLock = new Object();

    public byte[] transform(final ClassLoader loader, final String className,
            final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {

        final long startTime = System.nanoTime();

        ThreadTracer tt = null;
        boolean paused = false;
        try {
            if (this.tracingReady)
                return null;

            // disable tracing for the thread tracer of this thread
            tt = getThreadTracer();
            tt.pauseTracing();
            paused = true;

            final String javaClassName = Type.getObjectType(className).getClassName();
            if (isExcluded(javaClassName))
                return null;
            return transform0(className, javaClassName, classfileBuffer);
        } catch (final Throwable t) {
            System.err.println("Error transforming class " + className + ":");
            t.printStackTrace(System.err);
            return null;
        } finally {
            if (this.debug) {
                // first build the string, then print it. otherwise the output may be interrupted
                // when new classes need to be loaded to format the output
                final long nanoSecs = System.nanoTime() - startTime;
                this.totalTransformationTime.addAndGet(nanoSecs);
                this.totalTransformedClasses.incrementAndGet();
                final String text = String.format((Locale)null, "Transforming %s took %.2f msec.%n",
                        className, 1e-6*nanoSecs);
                System.out.print(text);
            }
            if (paused && tt != null)
                tt.unpauseTracing();
        }
   }

    private boolean isExcluded(final String javaClassName) {
        if (javaClassName.startsWith("de.unisb.cs.st.javaslicer."))
            return true;
        if (javaClassName.startsWith("de.hammacher.util."))
            return true;
        if (javaClassName.startsWith("de.unisb.cs.st.sequitur"))
            return true;

        //////////////////////////////////////////////////////////////////
        // NOTE: these will be cleaned up when the system runs stable
        //////////////////////////////////////////////////////////////////

        if (javaClassName.equals("java.lang.System"))
            return true;
        /*
        if (javaClassName.equals("java.lang.VerifyError")
                || javaClassName.equals("java.lang.ClassCircularityError")
                || javaClassName.equals("java.lang.LinkageError")
                || javaClassName.equals("java.lang.Error")
                || javaClassName.equals("java.lang.Throwable"))
            return null;
        */

        if (javaClassName.startsWith("java.util.Collections"))
            return true;

        if (javaClassName.startsWith("java.lang.Thread")
                && !"java.lang.Thread".equals(javaClassName))
            return true;
        // because of Thread.getName()
        if (javaClassName.equals("java.lang.String"))
            return true;
        if (javaClassName.equals("java.util.Arrays"))
            return true;
        if (javaClassName.equals("java.lang.Math"))
            return true;

        // Object
        if (javaClassName.equals("java.lang.Object"))
            return true;
        // references
        if (javaClassName.startsWith("java.lang.ref."))
            return true;

        return false;
    }

    private byte[] transform0(final String className, final String javaClassName, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);

        final ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        final ClassWriter writer;

        // we have to synchronize on System.out first.
        // otherwise it may lead to a deadlock if a thread calls removeStale() on ConcurrentReferenceHashMap
        // while he holds the lock for System.out, but another thread is inside the transformation step and
        // waits for the lock of System.out
        synchronized (System.out) { synchronized (this.transformationLock) {

            // register that class for later reconstruction of the trace
            final ReadClass readClass = new ReadClass(className, AbstractInstruction.getNextIndex(), classNode.access);

            //final boolean computeFrames = COMPUTE_FRAMES || Arrays.asList(this.pauseTracingClasses).contains(Type.getObjectType(className).getClassName());
            final boolean computeFrames = COMPUTE_FRAMES;

            writer = new FixedClassWriter(computeFrames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);

            final ClassVisitor output = this.check ? new CheckClassAdapter(writer) : writer;

            if (Arrays.asList(this.pauseTracingClasses).contains(javaClassName)
                    || className.startsWith("java/security/")) {
                new PauseTracingInstrumenter(readClass, this).transform(classNode);
            } else {
                if ("java/lang/Thread".equals(className))
                    new ThreadInstrumenter(readClass, this).transform(classNode);
                else
                    new TracingClassInstrumenter(readClass, this).transform(classNode);
            }

            new IdentifiableInstrumenter(readClass, this).transform(classNode);

            classNode.accept(computeFrames ? new JSRInliner(output) : output);

            readClass.setInstructionNumberEnd(AbstractInstruction.getNextIndex());

            // now we can write the class out
            // NOTE: we do not write it out immediately, because this sometimes leads
            // to circular dependencies!
            //readClass.writeOut(this.readClassesOutputStream, this.readClassesStringCache);
            this.readClasses.add(readClass);

        }}

        final byte[] newClassfileBuffer = writer.toByteArray();

        if (this.check) {
            checkClass(newClassfileBuffer, className);
        }

        //printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
        /*
        if (className.equals("java/lang/ClassLoader"))
            printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
        if (className.equals("java/util/zip/ZipFile"))
            printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
        if (className.endsWith("line/Main"))
            printClass(newClassfileBuffer, Type.getObjectType(className).getClassName());
        */

        return newClassfileBuffer;
    }

    public int getNextSequenceIndex() {
        return this.traceSequenceTypes.size();
    }

    private boolean checkClass(final byte[] newClassfileBuffer, final String classname) {
        final ClassNode cn = new ClassNode();
        final ClassReader cr = new ClassReader(newClassfileBuffer);
        //cr.accept(new CheckClassAdapter(cn), ClassReader.SKIP_DEBUG);
        cr.accept(new CheckClassAdapter(cn), 0);

        for (final Object methodObj : cn.methods) {
            final MethodNode method = (MethodNode) methodObj;
            final Analyzer a = new Analyzer(new BasicVerifier());
            //final Analyzer a = new Analyzer(new SimpleVerifier());
            try {
                a.analyze(cn.name, method);
            } catch (final AnalyzerException e) {
                System.err.println("Error in method " + classname + "." + method.name
                        + method.desc + ":");
                e.printStackTrace(System.err);
                printMethod(a, System.err, method);
                return false;
            }
        }
        return true;
    }

    private void printMethod(final Analyzer a, final PrintStream out, final MethodNode method) {
        final Frame[] frames = a.getFrames();

        final TraceMethodVisitor mv = new TraceMethodVisitor();

        out.println(method.name + method.desc);
        for (int j = 0; j < method.instructions.size(); ++j) {
            method.instructions.get(j).accept(mv);

            final StringBuffer s = new StringBuffer();
            final Frame f = frames[j];
            if (f == null) {
                s.append('?');
            } else {
                for (int k = 0; k < f.getLocals(); ++k) {
                    s.append(getShortName(f.getLocal(k).toString())).append(' ');
                }
                s.append(" : ");
                for (int k = 0; k < f.getStackSize(); ++k) {
                    s.append(getShortName(f.getStack(k).toString())).append(' ');
                }
            }
            while (s.length() < method.maxStack + method.maxLocals + 1) {
                s.append(' ');
            }
            out.print(Integer.toString(j + 100000).substring(1));
            out.print(" " + s + " : " + mv.text.get(j));
        }
        for (int j = 0; j < method.tryCatchBlocks.size(); ++j) {
            ((TryCatchBlockNode) method.tryCatchBlocks.get(j)).accept(mv);
            out.print(" " + mv.text.get(method.instructions.size()+j));
        }
        out.println(" MAXSTACK " + method.maxStack);
        out.println(" MAXLOCALS " + method.maxLocals);
        out.println();
    }

    @SuppressWarnings("unused")
    private void printClass(final byte[] classfileBuffer, final String classname) {
        /*
        final TraceClassVisitor v = new TraceClassVisitor(new PrintWriter(System.out));
        new ClassReader(classfileBuffer).accept(v, ClassReader.SKIP_DEBUG);
        */
        final ClassNode cn = new ClassNode();
        final ClassReader cr = new ClassReader(classfileBuffer);
        //cr.accept(new CheckClassAdapter(cn), ClassReader.SKIP_DEBUG);
        cr.accept(new CheckClassAdapter(cn), 0);

        for (final Object methodObj : cn.methods) {
            final MethodNode method = (MethodNode) methodObj;
            final Analyzer a = new Analyzer(new BasicVerifier());
            //final Analyzer a = new Analyzer(new SimpleVerifier());
            try {
                a.analyze(cn.name, method);
            } catch (final AnalyzerException e) {
                System.err.println("// error in method " + classname + "." + method.name
                        + method.desc + ":" + e);
            }
            printMethod(a, System.err, method);
        }
    }

    private static String getShortName(final String name) {
        final int n = name.lastIndexOf('/');
        int k = name.length();
        if (name.charAt(k - 1) == ';') {
            k--;
        }
        return n == -1 ? name : name.substring(n + 1, k);
    }

    public int newIntegerTraceSequence() {
        return newTraceSequence(TraceSequenceTypes.Type.INTEGER);
    }

    public int newLongTraceSequence() {
        return newTraceSequence(TraceSequenceTypes.Type.LONG);
    }

    private synchronized int newTraceSequence(final TraceSequenceTypes.Type type) {
        final int nextIndex = getNextSequenceIndex();
        this.traceSequenceTypes.add(type);
        return nextIndex;
    }

    public ThreadTracer getThreadTracer() {
        final Thread currentThread = Thread.currentThread();
        // exclude all (internal) untraced threads, as well as the
        // MultiplexedFileWriter autoflush thread
        if (currentThread instanceof UntracedThread)
            return NullThreadTracer.instance;
        ThreadTracer tracer = this.threadTracers.get(currentThread);
        if (tracer != null)
            return tracer;
        final ThreadTracer newTracer;
        synchronized (this.threadTracers) {
            // check if it's present now (should not be the case)...
            tracer = this.threadTracers.get(currentThread);
            if (tracer != null)
                return tracer;
            if (this.threadTracerBeingCreated == currentThread)
                return NullThreadTracer.instance;
            assert this.threadTracerBeingCreated == null;
            this.threadTracerBeingCreated = currentThread;
            if (this.tracingReady ||
                    currentThread.getClass().getPackage().equals(MultiplexedFileWriter.class.getPackage()))
                newTracer = NullThreadTracer.instance;
            else
                newTracer = new TracingThreadTracer(currentThread,
                            this.traceSequenceTypes, this);
            try {
                // we have to pause it, because put uses classes in the java api
                newTracer.pauseTracing();
                final ThreadTracer oldTracer = this.threadTracers.put(currentThread, newTracer);
                assert oldTracer == null;
            } finally {
                assert this.threadTracerBeingCreated == currentThread;
                this.threadTracerBeingCreated = null;
                // recheck tracingReady!
                if (this.tracingStarted && !this.tracingReady)
                    newTracer.unpauseTracing();
            }
        }
        synchronized (this.readyThreadTracers) {
            if (this.readyThreadTracers.size() > 0) {
                newTracer.pauseTracing();
                try {
                    for (final TracingThreadTracer t: this.readyThreadTracers)
                        writeOutIfNecessary(t);
                } finally {
                    newTracer.unpauseTracing();
                }
            }
        }
        return newTracer;
    }

    public void threadExits() {
        try {
            final Thread exitingThread = Thread.currentThread();
            final ThreadTracer threadTracer = this.threadTracers.get(exitingThread);
            if (threadTracer != null)
                threadTracer.pauseTracing();
            if (threadTracer instanceof TracingThreadTracer) {
                final TracingThreadTracer ttt = (TracingThreadTracer) threadTracer;
                assert ttt.getThreadId() == exitingThread.getId();
                writeOutIfNecessary(ttt);
            }
            this.threadTracers.put(exitingThread, NullThreadTracer.instance);
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private void writeOutIfNecessary(final TracingThreadTracer threadTracer) {
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch oldLatch = this.writtenThreadTracers.putIfAbsent(threadTracer, latch);
        if (oldLatch == null) {
            try {
                threadTracer.finish();
                synchronized (this.threadTracersOutputStream) {
                    threadTracer.writeOut(this.threadTracersOutputStream);
                }
            } catch (final IOException e) {
                error(e);
            } finally {
                latch.countDown();
            }
        } else {
            try {
                oldLatch.await();
            } catch (final InterruptedException e) {
                // reset interrupted flag, but continue
                Thread.currentThread().interrupt();
            }
        }
    }

    private final Object finishLock = new Object();
    public void finish() throws IOException {
        synchronized (this.finishLock) {
            if (this.tracingReady)
                return;
            this.tracingReady = true;
            this.instrumentation.removeTransformer(this);
            if (this.debug) {
                TracingMethodInstrumenter.printStats(System.out);
                System.out.format((Locale)null, "Transforming %d classes took %.3f seconds in total.%n",
                        this.totalTransformedClasses.get(), 1e-9*this.totalTransformationTime.get());
            }
            final List<TracingThreadTracer> allThreadTracers = new ArrayList<TracingThreadTracer>();
            synchronized (this.threadTracers) {
                for (final Entry<Thread, ThreadTracer> e: this.threadTracers.entrySet()) {
                    final ThreadTracer t = e.getValue();
                    e.setValue(NullThreadTracer.instance);
                    if (t instanceof TracingThreadTracer)
                        allThreadTracers.add((TracingThreadTracer) t);
                }
            }
            synchronized (this.readyThreadTracers) {
                allThreadTracers.addAll(this.readyThreadTracers);
                this.readyThreadTracers.clear();
            }
            for (final TracingThreadTracer t: allThreadTracers) {
                writeOutIfNecessary(t);
            }
            this.threadTracersOutputStream.close();

            ReadClass rc;
            while ((rc = this.readClasses.poll()) != null)
                rc.writeOut(this.readClassesOutputStream, this.readClassesStringCache);
            this.readClassesOutputStream.close();
            this.file.close();
        }
    }

    public MultiplexOutputStream newOutputStream() {
        return this.file.newOutputStream();
    }

    public void printFinalUserInfo() {
        if (this.errorCount.get() == 1) {
            System.out.println("There was an error while tracing: " + this.lastErrorString);
        } else if (this.errorCount.get() > 1) {
            System.out.println("There were several errors (" + this.errorCount.get() + ") while tracing.");
            System.out.println("Last error message: " + this.lastErrorString);
        } else {
            if (this.debug)
                System.out.println("DEBUG: trace written successfully");
        }
    }

    /**
     * Checks whether the class given by the fully qualified java class name has been
     * redefined by the instrumenter or not.
     * The classes that couldn't get redefined are those already loaded by the vm when
     * the agent's premain method was executed.
     *
     * @param className the fully qualified classname to check
     * @return true if the class was redefined, false if not
     */
    // hmm, redefined is the wrong word here...
    public boolean wasRedefined(final String className) {
        return !this.notRedefinedClasses.contains(className);
    }

}