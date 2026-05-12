package com.m2049r.xmrwallet.model;

import lombok.Getter;
import lombok.Setter;

public class PendingTransaction {
    
    public long handle;
    
    PendingTransaction(long handle) {
        this.handle = handle;
    }
    
    public enum Status {
        Status_Ok(0),
        Status_Error(1),
        Status_Critical(2);
        
        private final int value;
        
        Status(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static Status fromInteger(int x) {
            switch (x) {
                case 0:
                    return Status_Ok;
                case 1:
                    return Status_Error;
                case 2:
                    return Status_Critical;
                default:
                    return Status_Error;
            }
        }
    }
    
    public enum Priority {
        Priority_Default(0),
        Priority_Low(1),
        Priority_Medium(2),
        Priority_High(3),
        Priority_Last(4);
        
        private final int value;
        
        Priority(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static Priority fromInteger(int x) {
            switch (x) {
                case 0:
                    return Priority_Default;
                case 1:
                    return Priority_Low;
                case 2:
                    return Priority_Medium;
                case 3:
                    return Priority_High;
                case 4:
                    return Priority_Last;
                default:
                    return Priority_Default;
            }
        }
    }
    
    public Status getStatus() {
        return Status.values()[getStatusJ()];
    }
    
    public native int getStatusJ();
    
    public native String getErrorString();
    
    // commit transaction or save to file if filename is provided.
    public native boolean commit(String filename, boolean overwrite);
    
    public native long getAmount();
    
    public native long getDust();
    
    public native long getFee();
    
    public String getFirstTxId() {
        String id = getFirstTxIdJ();
        if (id == null)
            throw new IndexOutOfBoundsException();
        return id;
    }
    
    public native String getFirstTxIdJ();
    
    public native long getTxCount();
    
    @Getter
    @Setter
    private long pocketChange;
    
    public long getNetAmount() {
        return getAmount() - pocketChange;
    }
    
    /* ---------------------------------------------------------------------
     * Multisig Methods
     * ------------------------------------------------------------------ */
    
    /**
     * Export multisig sign data from a pending transaction.
     * This data is shared with other signers so they can sign the transaction.
     * 
     * @return Multisig transaction data as a string
     */
    public native String multisigSignData();
    
    /**
     * Sign a multisig transaction.
     * This method modifies the transaction in place by adding this participant's signature.
     */
    public native void signMultisigTx();
    
    /**
     * Get the list of public keys of signers who have already signed this transaction.
     * 
     * @return ArrayList of signer public keys as strings
     */
    private native Object getSignersKeysJ();
    
    /**
     * Get the list of public keys of signers who have already signed this transaction.
     * 
     * @return List of signer public keys
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> getSignersKeys() {
        Object result = getSignersKeysJ();
        if (result instanceof java.util.List) {
            return (java.util.List<String>) result;
        }
        return new java.util.ArrayList<>();
    }
    
    /**
     * Get transaction key for this pending transaction.
     * Useful for creating transaction proofs.
     * 
     * Note: This returns the multisig sign data which can be used as a transaction identifier
     * in multisig scenarios. For regular transactions, use Wallet.getTxKey() after commit.
     * 
     * @return Transaction data/key
     */
    public String getTxKey() {
        return multisigSignData();
    }
}
