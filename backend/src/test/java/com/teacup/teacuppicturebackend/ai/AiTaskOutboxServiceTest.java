package com.teacup.teacuppicturebackend.ai;

import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskOutboxMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTaskOutbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiTaskOutboxServiceTest {
    private final AiTaskOutboxMapper outboxMapper = mock(AiTaskOutboxMapper.class);
    private final AiTaskOutboxService service = new AiTaskOutboxService(outboxMapper, mock(AiTaskMapper.class));

    @Test
    void enqueueCreatesPendingEventForTask() {
        service.enqueue(31L);

        ArgumentCaptor<AiTaskOutbox> captor = ArgumentCaptor.forClass(AiTaskOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        AiTaskOutbox row = captor.getValue();
        assertEquals(31L, row.getTaskId());
        assertEquals("pending", row.getStatus());
        assertEquals(0, row.getAttemptCount());
        assertNotNull(row.getEventId());
        assertNotNull(row.getNextAttemptAt());
    }
}
