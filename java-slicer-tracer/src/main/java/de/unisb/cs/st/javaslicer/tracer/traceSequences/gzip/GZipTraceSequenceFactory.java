package de.unisb.cs.st.javaslicer.tracer.traceSequences.gzip;

import java.io.IOException;
import java.io.OutputStream;

import de.unisb.cs.st.javaslicer.tracer.ThreadTracer;
import de.unisb.cs.st.javaslicer.tracer.Tracer;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.ObjectTraceSequence;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequence;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequenceFactory;
import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequence.Type;

public class GZipTraceSequenceFactory implements TraceSequenceFactory, TraceSequenceFactory.PerThread {

    @Override
    public TraceSequence createTraceSequence(final Type type, final Tracer tracer) throws IOException {
        switch (type) {
        case INTEGER:
            return new GZipIntegerTraceSequence(tracer);
        case LONG:
            return new GZipLongTraceSequence(tracer);
        case OBJECT:
            return new ObjectTraceSequence(new GZipLongTraceSequence(tracer));
        default:
            assert false;
            return null;
        }
    }

    @Override
    public void finish() {
        // nop
    }

    @Override
    public PerThread forThreadTracer(final ThreadTracer tt) {
        return this;
    }

    @Override
    public void writeOut(final OutputStream out) throws IOException {
        out.write(TraceSequence.FORMAT_GZIP);
    }

}
