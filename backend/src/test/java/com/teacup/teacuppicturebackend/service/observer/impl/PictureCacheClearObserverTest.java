package com.teacup.teacuppicturebackend.service.observer.impl;

import com.teacup.teacuppicturebackend.cache.PublicPictureInvalidation;
import com.teacup.teacuppicturebackend.model.event.ClearEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PictureCacheClearObserverTest {
    @Test
    void writesOneDurableInvalidationWithoutScanningCacheKeys() {
        PublicPictureInvalidation outbox = mock(PublicPictureInvalidation.class);
        PictureCacheClearObserver observer = new PictureCacheClearObserver(outbox);

        observer.handleClearEvent(ClearEvent.of("UPDATE", "PICTURE", 42L));

        verify(outbox).pictureChanged(42L);
        verify(outbox, never()).pictureChanged(0L);
    }
}
