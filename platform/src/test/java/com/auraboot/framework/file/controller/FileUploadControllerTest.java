package com.auraboot.framework.file.controller;

import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    @Mock
    private FileService fileService;

    @Mock
    private StorageProvider storageProvider;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FileUploadController controller = new FileUploadController();
        ReflectionTestUtils.setField(controller, "fileService", fileService);
        ReflectionTestUtils.setField(controller, "storageProvider", storageProvider);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void downloadFile_svgImage_returnsInlineSvgResponse() throws Exception {
        FileEntity file = storedFile("board.svg", "image/svg+xml", "/tmp/board.svg");
        when(fileService.getFileById("01KV22CQ7PKX3W50Y7MM575ACK")).thenReturn(file);
        when(storageProvider.download("/tmp/board.svg"))
                .thenReturn(new ByteArrayInputStream("<svg/>".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/01KV22CQ7PKX3W50Y7MM575ACK"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/svg+xml"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"board.svg\""))
                .andExpect(content().string("<svg/>"));
    }

    @Test
    void downloadFile_nonImage_keepsAttachmentDisposition() throws Exception {
        FileEntity file = storedFile("quote.pdf", "application/pdf", "/tmp/quote.pdf");
        when(fileService.getFileById("file-pid")).thenReturn(file);
        when(storageProvider.download("/tmp/quote.pdf"))
                .thenReturn(new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/file/download/file-pid"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote.pdf\""))
                .andExpect(content().string("pdf"));
    }

    private FileEntity storedFile(String originalName, String mimeType, String localPath) {
        FileEntity file = new FileEntity();
        file.setPid("file-pid");
        file.setOriginalName(originalName);
        file.setMimeType(mimeType);
        file.setLocalPath(localPath);
        return file;
    }
}
