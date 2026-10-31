package dev.zeroinput.copyprobe;

final class RecognizerFactory {
    private RecognizerFactory() { }
    static LineRecognizer create() { return new LocalLineRecognizer(); }
}
