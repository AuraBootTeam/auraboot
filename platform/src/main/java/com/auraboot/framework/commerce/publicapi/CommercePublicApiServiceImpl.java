package com.auraboot.framework.commerce.publicapi;

import com.auraboot.framework.commerce.publicapi.dto.StorefrontBootstrapResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductDetailResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductListResponse;
import com.auraboot.framework.tenant.dao.entity.Store;
import com.auraboot.framework.tenant.dao.mapper.StoreMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CommercePublicApiServiceImpl implements CommercePublicApiService {

    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final String DEFAULT_CURRENCY = "CNY";
    private static final String DEFAULT_THEME_ID = "default";

    private final StoreMapper storeMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<StorefrontBootstrapResponse> bootstrap(String storeHandle) {
        return findStore(storeHandle).map(this::toBootstrap);
    }

    @Override
    public Optional<StorefrontProductListResponse> products(
            String storeHandle,
            String collectionHandle,
            String query,
            String cursor,
            Integer pageSize
    ) {
        return findStore(storeHandle).map(store -> new StorefrontProductListResponse(List.of(), 0L, null));
    }

    @Override
    public Optional<StorefrontProductDetailResponse> product(String storeHandle, String handle) {
        if (findStore(storeHandle).isEmpty() || !StringUtils.hasText(handle)) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<Store> findStore(String storeHandle) {
        if (!StringUtils.hasText(storeHandle)) {
            return Optional.empty();
        }
        List<Store> stores = storeMapper.findPublicCandidatesByCode(storeHandle.trim());
        if (stores == null || stores.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(stores.get(0));
    }

    private StorefrontBootstrapResponse toBootstrap(Store store) {
        JsonNode commerce = readCommerceNode(store.getExtension());
        JsonNode themeNode = commerce.path("theme");
        String themeId = textValue(themeNode, "themeId", DEFAULT_THEME_ID);
        String themeVersion = textValue(themeNode, "version", null);

        return new StorefrontBootstrapResponse(
                store.getCode(),
                store.getName(),
                textValue(commerce, "locale", DEFAULT_LOCALE),
                textValue(commerce, "currencyCode", DEFAULT_CURRENCY),
                new StorefrontBootstrapResponse.ThemeRef(themeId, themeVersion, null),
                List.of(new StorefrontBootstrapResponse.ChannelRef("online-store", "Online Store"))
        );
    }

    private JsonNode readCommerceNode(String extension) {
        if (!StringUtils.hasText(extension)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(extension);
            return root.path("commerce");
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String textValue(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.path(fieldName);
        if (value.isTextual() && StringUtils.hasText(value.asText())) {
            return value.asText();
        }
        return defaultValue;
    }
}
