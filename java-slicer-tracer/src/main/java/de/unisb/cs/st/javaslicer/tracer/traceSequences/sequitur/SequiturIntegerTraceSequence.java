package de.unisb.cs.st.javaslicer.tracer.traceSequences.sequitur;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import de.unisb.cs.st.javaslicer.tracer.traceSequences.TraceSequence.IntegerTraceSequence;
import de.unisb.cs.st.javaslicer.tracer.util.OptimizedDataOutputStream;
import de.unisb.cs.st.javaslicer.tracer.util.sequitur.output.OutputSequence;

public class SequiturIntegerTraceSequence implements IntegerTraceSequence {

    private boolean ready = false;

    private long sequenceOffset;

    private int[] values = new int[10];
    private int count = 0;

    private int lastValue = 0;

    private final OutputSequence<Integer> sequiturSeq;
    private final AtomicLong sequiturSeqLength;

    public SequiturIntegerTraceSequence(final OutputSequence<Integer> outputSequence, final AtomicLong outputSeqLength) {
        this.sequiturSeq = outputSequence;
        this.sequiturSeqLength = outputSeqLength;
    }

    public void trace(final int value) {
        assert !this.ready: "Trace cannot be extended any more";

        if (this.count == this.values.length)
            this.values = Arrays.copyOf(this.values, this.values.length*3/2);
        this.values[this.count++] = value - this.lastValue;
        this.lastValue = value;
    }

    public void writeOut(final DataOutputStream out) throws IOException {
        finish();

        OptimizedDataOutputStream.writeLong0(this.sequenceOffset, out);
        OptimizedDataOutputStream.writeInt0(this.count, out);
    }

    public void finish() {
        if (this.ready)
            return;
        this.ready = true;
        synchronized (this.sequiturSeq) {
            for (int i = 0; i < this.count; ++i)
                this.sequiturSeq.append(this.values[i]);
            this.values = null;
            this.sequiturSeq.append(this.lastValue);
            this.sequenceOffset = this.sequiturSeqLength.getAndAdd(this.count+1);
        }
    }

    @Override
    public boolean useMultiThreading() {
        return false;
    }

}
