package com.bocbang.backend.stt;

class FakeSpeechToTextEngineContractTest extends SpeechToTextEngineContract {

    @Override
    protected SpeechToTextEngine engine() {
        return new FakeSpeechToTextEngine("xin chào");
    }
}
