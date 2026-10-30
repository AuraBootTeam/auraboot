package com.auraboot.framework.notification.wechat;

import jakarta.servlet.http.HttpServletRequest;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** Provider-authenticated safe-mode receiver, absent unless explicitly enabled and configured. */
@RestController
@RequestMapping(value = "/api/public/wechat-mini/subscription-events", produces = MediaType.TEXT_PLAIN_VALUE)
@ConditionalOnProperty(prefix = "aura.notification.wechat-subscription.callback", name = "enabled", havingValue = "true")
@AuthenticatedAccess("WeChat provider callback, JWT-exempt; the receiver verifies signature, safe-mode ciphertext, AppID and account before any consent write")
public class WechatSubscriptionCallbackController {
    private final WechatSubscriptionConsentReceiver receiver;
    public WechatSubscriptionCallbackController(WechatSubscriptionConsentReceiver receiver) { this.receiver = receiver; }

    @GetMapping
    public String verify(@RequestParam String signature, @RequestParam String timestamp,
            @RequestParam String nonce, @RequestParam String echostr) {
        return receiver.verifyUrl(signature, timestamp, nonce, echostr);
    }

    @PostMapping
    public String receive(@RequestParam(name = "encrypt_type", required = false) String encryptType,
            @RequestParam(name = "msg_signature", required = false) String signature,
            @RequestParam String timestamp, @RequestParam String nonce, HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(WechatSubscriptionCallbackVerifier.MAX_BODY_BYTES + 1);
        if (body.length > WechatSubscriptionCallbackVerifier.MAX_BODY_BYTES) {
            throw WechatSubscriptionCallbackVerifier.rejected("CALLBACK_BODY_INVALID");
        }
        // The proxied transactional receiver commits first; a persistence error is not acknowledged as success.
        receiver.receive(encryptType, signature, timestamp, nonce, body);
        return "success";
    }

    @ExceptionHandler(WechatSubscriptionCallbackVerifier.CallbackRejected.class)
    public ResponseEntity<String> invalidRequest() {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("invalid request");
    }
}
