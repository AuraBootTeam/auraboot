package com.auraboot.framework.notification.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Element;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.auraboot.framework.notification.wechat.WechatSubscriptionCallbackVerifier.*;

/** Normalizes authenticated provider events only. Persistence, deduplication and quota reservation remain separate. */
public final class WechatSubscriptionCallbackEvents {
    private WechatSubscriptionCallbackEvents() { }

    public static Event decode(VerifiedPayload verified) {
        if (verified == null) throw rejected("CALLBACK_VERIFICATION_REQUIRED");
        Parsed fields = verified.parsed();
        if (!"event".equals(fields.text("MsgType"))) throw rejected("CALLBACK_EVENT_UNSUPPORTED");
        Kind kind = switch (fields.text("Event")) {
            case "subscribe_msg_popup_event" -> Kind.POPUP;
            case "subscribe_msg_change_event" -> Kind.CHANGE;
            case "subscribe_msg_sent_event" -> Kind.SENT;
            default -> throw rejected("CALLBACK_EVENT_UNSUPPORTED");
        };
        String openid = identifier(fields.text("FromUserName"), 128);
        long created = digits(fields.text("CreateTime"), 12);
        List<Parsed> entries = entries(fields, kind);
        List<Item> items = new ArrayList<>();
        for (Parsed entry : entries) {
            String templateId = identifier(entry.text("TemplateId"), 128);
            if (kind == Kind.SENT) {
                String messageId = entry.text("MsgID");
                if (!messageId.matches("[0-9]{1,32}")) throw rejected("CALLBACK_FIELD_INVALID");
                long error = digits(entry.text("ErrorCode"), 10);
                if (error > Integer.MAX_VALUE) throw rejected("CALLBACK_FIELD_INVALID");
                String errorStatus = entry.text("ErrorStatus");
                if (errorStatus.length() > 256) throw rejected("CALLBACK_FIELD_INVALID");
                items.add(new Item(templateId, null, null, messageId, (int) error, errorStatus));
            } else {
                String status = entry.text("SubscribeStatusString");
                if (!("reject".equals(status) || kind == Kind.POPUP && "accept".equals(status))) {
                    throw rejected("CALLBACK_FIELD_INVALID");
                }
                Long scene = kind == Kind.POPUP ? digits(entry.text("PopupScene"), 10) : null;
                if (scene != null && scene > Integer.MAX_VALUE) throw rejected("CALLBACK_FIELD_INVALID");
                items.add(new Item(templateId, status, scene, null, null, null));
            }
        }
        if (items.stream().map(Item::templateId).distinct().count() != items.size()) {
            throw rejected("CALLBACK_DUPLICATE_TEMPLATE");
        }
        return new Event(verified.appId(), openid, Instant.ofEpochSecond(created), kind, List.copyOf(items));
    }

    private static List<Parsed> entries(Parsed fields, Kind kind) {
        List<Parsed> result = new ArrayList<>();
        if (fields.json() != null) {
            JsonNode list = fields.json().get("List");
            if (list == null) throw rejected("CALLBACK_LIST_INVALID");
            if (list.isObject()) result.add(new Parsed(list, null));
            else if (list.isArray()) {
                if (list.size() > 20) throw rejected("CALLBACK_LIST_INVALID");
                for (JsonNode item : list) {
                    if (!item.isObject()) throw rejected("CALLBACK_LIST_INVALID");
                    result.add(new Parsed(item, null));
                }
            } else throw rejected("CALLBACK_LIST_INVALID");
        } else {
            String wrapper = switch (kind) {
                case POPUP -> "SubscribeMsgPopupEvent";
                case CHANGE -> "SubscribeMsgChangeEvent";
                case SENT -> "SubscribeMsgSentEvent";
            };
            Element found = null;
            for (var node = fields.xml().getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && wrapper.equals(element.getTagName())) {
                    if (found != null) throw rejected("CALLBACK_LIST_INVALID");
                    found = element;
                }
            }
            if (found == null) throw rejected("CALLBACK_LIST_INVALID");
            for (var node = found.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    if (!"List".equals(element.getTagName()) || result.size() >= 20) throw rejected("CALLBACK_LIST_INVALID");
                    result.add(new Parsed(null, element));
                }
            }
        }
        if (result.isEmpty()) throw rejected("CALLBACK_LIST_INVALID");
        return result;
    }

    private static long digits(String value, int maxLength) {
        if (value.length() > maxLength || !value.matches("[0-9]+")) throw rejected("CALLBACK_FIELD_INVALID");
        return Long.parseLong(value);
    }
    private static String identifier(String value, int maxLength) {
        if (value.length() > maxLength || !value.matches("[A-Za-z0-9_-]+")) throw rejected("CALLBACK_FIELD_INVALID");
        return value;
    }
    public enum Kind { POPUP, CHANGE, SENT }
    public record Event(String appId, String openid, Instant createdAt, Kind kind, List<Item> items) {
        public Event { items = List.copyOf(items); }
        @Override public String toString() { return "WechatSubscriptionEvent[redacted]"; }
    }
    public record Item(String templateId, String status, Long popupScene, String messageId, Integer errorCode, String errorStatus) {
        @Override public String toString() { return "WechatSubscriptionEventItem[redacted]"; }
    }
}
