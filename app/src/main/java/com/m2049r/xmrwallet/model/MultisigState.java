package com.m2049r.xmrwallet.model;

public class MultisigState {
    public final boolean isMultisig;
    public final int isReady;
    public final int threshold;
    public final int total;

    public MultisigState(boolean isMultisig, int isReady, int threshold, int total) {
        this.isMultisig = isMultisig;
        this.isReady = isReady;
        this.threshold = threshold;
        this.total = total;
    }
}
