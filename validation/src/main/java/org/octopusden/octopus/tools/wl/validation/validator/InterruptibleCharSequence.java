package org.octopusden.octopus.tools.wl.validation.validator;

public class InterruptibleCharSequence implements CharSequence {

    private final CharSequence inner;

    public InterruptibleCharSequence(CharSequence inner) {
        super();
        this.inner = inner;
    }

    @Override
    public char charAt(int index) {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedRuntimeException();
        }
        return inner.charAt(index);
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new InterruptibleCharSequence(inner.subSequence(start, end));
    }

    @Override
    public String toString() {
        return inner.toString();
    }

    static class InterruptedRuntimeException extends RuntimeException {

    }
}
