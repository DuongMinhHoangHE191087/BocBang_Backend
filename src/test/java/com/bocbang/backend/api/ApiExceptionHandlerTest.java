package com.bocbang.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.bocbang.backend.stt.SpeechRecognitionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void answersARecognitionFailureWithoutServerPathsOrNativeMessages() {
        ProblemDetail problem = handler.handleRecognitionFailure(
                new SpeechRecognitionException("ASR model file not found: C:\\secret\\models\\qwen3.gguf"));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getDetail()).isNotBlank().doesNotContain("secret").doesNotContain("gguf");
    }

    @Test
    void stillExplainsAClientMistakeInItsOwnWords() {
        ProblemDetail problem = handler.handleBadRequest(new IllegalArgumentException("Audio file is empty"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Audio file is empty");
    }
}
