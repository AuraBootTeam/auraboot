package com.auraboot.framework.email.service.impl;

import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.service.EmailMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link EmailMessageService}. Pure record-state writes; no business
 * rules beyond the update itself.
 */
@Service
@RequiredArgsConstructor
public class EmailMessageServiceImpl implements EmailMessageService {

    private final EmailMessageMapper emailMessageMapper;

    @Override
    public void markRead(Long id) {
        EmailMessage message = new EmailMessage();
        message.setId(id);
        message.setIsRead(true);
        emailMessageMapper.updateById(message);
    }
}
