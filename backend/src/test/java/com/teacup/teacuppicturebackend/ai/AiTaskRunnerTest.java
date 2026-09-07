package com.teacup.teacuppicturebackend.ai;

import com.teacup.teacuppicturebackend.api.v1.M1Service;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskRunnerTest {
    private final PictureStorage storage = mock(PictureStorage.class);
    private final AiTaskMapper taskMapper = mock(AiTaskMapper.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private AiTaskRunner runner;

    @BeforeEach
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        runner = new AiTaskRunner(taskMapper, mock(PictureMapper.class), mock(UserMapper.class),
                mock(AiProviderRegistry.class), storage, mock(PersonalSpaceService.class), mock(M1Service.class),
                mock(AiTaskService.class), transactions, 15);
    }

    @Test
    void claimsQueuedTaskWithConditionalUpdateBeforeLoadingIt() {
        AiTask task = new AiTask();
        task.setId(31L);
        task.setStatus("running");
        when(taskMapper.claimQueued(eq(31L), any())).thenReturn(1);
        when(taskMapper.selectById(31L)).thenReturn(task);

        assertSame(task, runner.markRunning(31L));
        verify(taskMapper).claimQueued(eq(31L), any());
        verify(taskMapper).selectById(31L);
    }

    @Test
    void skipsProviderClaimWhenTaskIsNoLongerQueued() {
        when(taskMapper.claimQueued(eq(31L), any())).thenReturn(0);

        assertNull(runner.markRunning(31L));
        verify(taskMapper).claimQueued(eq(31L), any());
        verify(taskMapper, org.mockito.Mockito.never()).selectById(31L);
    }

    @Test
    void storesDecodedBase64ResultInPictureStorage() throws Exception {
        PictureStorage.StoredPicture stored = storedPicture();
        when(storage.store(any(InputStream.class), eq("generated.png"), eq("image/png"), eq(7L)))
                .thenReturn(stored);

        PictureStorage.StoredPicture result = runner.storeResult(
                AiProviderResult.base64("task-1", "request-1", "aGVsbG8=", "image/png"), 7L);

        ArgumentCaptor<InputStream> input = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).store(input.capture(), eq("generated.png"), eq("image/png"), eq(7L));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.getValue().readAllBytes());
        assertSame(stored, result);
    }

    @Test
    void importsUrlResultThroughPictureStorage() {
        PictureStorage.StoredPicture stored = storedPicture();
        String url = "https://example.test/generated.png";
        when(storage.importUrl(url, 7L)).thenReturn(stored);

        assertSame(stored, runner.storeResult(AiProviderResult.url("task-1", "request-1", url), 7L));
        verify(storage).importUrl(url, 7L);
    }

    @Test
    void rejectsInvalidBase64Result() {
        AiProviderException exception = assertThrows(AiProviderException.class, () -> runner.storeResult(
                AiProviderResult.base64("task-1", "request-1", "not-base64!", "image/png"), 7L));

        assertEquals("provider_invalid_image", exception.getCode());
    }

    private static PictureStorage.StoredPicture storedPicture() {
        return new PictureStorage.StoredPicture("objects/generated.png", 5, 1, 1, "png", "image/png", "checksum");
    }
}
