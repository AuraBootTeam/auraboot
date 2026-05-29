package com.auraboot.framework.review.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.review.dto.ReviewCreateRequest;
import com.auraboot.framework.review.dto.ReviewResponse;
import com.auraboot.framework.review.dto.ReviewSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final String REVIEW_TABLE = "ab_review";
    private static final String VOTE_TABLE = "ab_review_vote";
    private static final int MAX_REPLY_DEPTH = 10;
    private static final Set<String> RATING_REQUIRED_TARGETS = Set.of("marketplace_plugin");
    private static final Set<String> MARKETPLACE_STATS_TARGETS = Set.of("marketplace_plugin", "marketplace_solution");

    private final JdbcTemplate jdbcTemplate;

    public List<ReviewResponse> listReviews(String targetType, String targetId, String sort) {
        String normalizedTargetType = normalizeTargetType(targetType);
        String normalizedTargetId = requireText(targetId, "targetId is required");
        String orderBy = "newest".equalsIgnoreCase(sort)
                ? "created_at DESC"
                : "helpful_count DESC, created_at DESC";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT pid, target_type, target_id, parent_id, user_id,
                       COALESCE(CAST(user_id AS VARCHAR), 'Anonymous') AS user_name,
                       rating, title, content, helpful_count, reply_count, created_at, updated_at
                  FROM ab_review
                 WHERE target_type = ? AND target_id = ? AND status = 'visible'
                 ORDER BY %s
                """.formatted(orderBy),
                normalizedTargetType, normalizedTargetId);
        return rows.stream().map(this::toReviewResponse).toList();
    }

    public ReviewSummaryResponse summarize(String targetType, String targetId) {
        String normalizedTargetType = normalizeTargetType(targetType);
        String normalizedTargetId = requireText(targetId, "targetId is required");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT COUNT(*) FILTER (WHERE parent_id IS NULL) AS total_count,
                       ROUND(AVG(rating) FILTER (WHERE parent_id IS NULL AND rating IS NOT NULL), 2) AS average_rating,
                       COUNT(*) FILTER (WHERE parent_id IS NULL AND rating = 1) AS rating_1,
                       COUNT(*) FILTER (WHERE parent_id IS NULL AND rating = 2) AS rating_2,
                       COUNT(*) FILTER (WHERE parent_id IS NULL AND rating = 3) AS rating_3,
                       COUNT(*) FILTER (WHERE parent_id IS NULL AND rating = 4) AS rating_4,
                       COUNT(*) FILTER (WHERE parent_id IS NULL AND rating = 5) AS rating_5
                  FROM ab_review
                 WHERE target_type = ? AND target_id = ? AND status = 'visible'
                """,
                normalizedTargetType, normalizedTargetId);

        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int rating = 5; rating >= 1; rating--) {
            distribution.put(rating, longValue(row.get("rating_" + rating)));
        }
        return new ReviewSummaryResponse(
                normalizedTargetType,
                normalizedTargetId,
                longValue(row.get("total_count")),
                decimalValue(row.get("average_rating")),
                distribution);
    }

    @Transactional
    public ReviewResponse createReview(ReviewCreateRequest request) {
        Long userId = requireCurrentUserId();
        Long tenantId = requireCurrentTenantId();
        String targetType = normalizeTargetType(request.targetType());
        String targetId = requireText(request.targetId(), "targetId is required");
        String content = requireText(request.content(), "content is required");
        String title = normalizeOptionalText(request.title());
        String parentId = normalizeOptionalText(request.parentId());
        Integer rating = normalizeRating(request.rating(), parentId == null, targetType);

        ParentReview parent = parentId == null ? null : requireParent(parentId, targetType, targetId);
        int depth = parent == null ? 0 : parent.depth() + 1;
        if (depth > MAX_REPLY_DEPTH) {
            throw new BusinessException(ResponseCode.BadParam, "Maximum review reply depth exceeded");
        }
        String rootId = parent == null ? null : (parent.rootId() == null ? parent.pid() : parent.rootId());
        if (parent == null) {
            requireNoExistingTopLevelReview(targetType, targetId, userId);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                INSERT INTO ab_review
                       (pid, target_type, target_id, user_id, tenant_id, parent_id, root_id,
                        depth, rating, title, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                RETURNING pid, target_type, target_id, parent_id, user_id,
                          COALESCE(CAST(user_id AS VARCHAR), 'Anonymous') AS user_name,
                          rating, title, content, helpful_count, reply_count, created_at, updated_at
                """,
                UniqueIdGenerator.generate(),
                targetType,
                targetId,
                userId,
                tenantId,
                parentId,
                rootId,
                depth,
                rating,
                title,
                content);
        if (rows.isEmpty()) {
            throw new BusinessException(ResponseCode.SystemError, "Review was not created");
        }

        if (parent != null) {
            jdbcTemplate.update(
                    "UPDATE " + REVIEW_TABLE + " SET reply_count = reply_count + 1, updated_at = NOW() WHERE pid = ?",
                    parent.pid());
        } else {
            refreshMarketplaceStats(targetType, targetId);
        }
        return toReviewResponse(rows.get(0));
    }

    @Transactional
    public boolean vote(String reviewPid, String voteType) {
        Long userId = requireCurrentUserId();
        String normalizedReviewPid = requireText(reviewPid, "review pid is required");
        String normalizedVoteType = normalizeVoteType(voteType);
        requireReviewExists(normalizedReviewPid);

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO %s (pid, review_pid, user_id, vote_type, created_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (review_pid, user_id) DO NOTHING
                """.formatted(VOTE_TABLE),
                UniqueIdGenerator.generate(),
                normalizedReviewPid,
                userId,
                normalizedVoteType);
        if (inserted == 0) {
            return false;
        }
        String column = "report".equals(normalizedVoteType) ? "report_count" : "helpful_count";
        jdbcTemplate.update(
                "UPDATE " + REVIEW_TABLE + " SET " + column + " = " + column + " + 1, updated_at = NOW() WHERE pid = ?",
                normalizedReviewPid);
        return true;
    }

    private ParentReview requireParent(String parentId, String targetType, String targetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT pid, target_type, target_id, root_id, depth
                  FROM ab_review
                 WHERE pid = ? AND status = 'visible'
                """,
                parentId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResponseCode.BadParam, "Parent review not found: " + parentId);
        }
        Map<String, Object> row = rows.get(0);
        if (!targetType.equals(stringValue(row.get("target_type"))) || !targetId.equals(stringValue(row.get("target_id")))) {
            throw new BusinessException(ResponseCode.BadParam, "Parent review belongs to a different target");
        }
        return new ParentReview(
                stringValue(row.get("pid")),
                stringValue(row.get("root_id")),
                intValue(row.get("depth")));
    }

    private void requireNoExistingTopLevelReview(String targetType, String targetId, Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT pid
                  FROM ab_review
                 WHERE target_type = ? AND target_id = ? AND user_id = ? AND parent_id IS NULL
                 LIMIT 1
                """,
                targetType, targetId, userId);
        if (!rows.isEmpty()) {
            throw new BusinessException(ResponseCode.BadParam, "User already reviewed this target");
        }
    }

    private void requireReviewExists(String reviewPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid FROM " + REVIEW_TABLE + " WHERE pid = ? AND status = 'visible'",
                reviewPid);
        if (rows.isEmpty()) {
            throw new BusinessException(ResponseCode.BadParam, "Review not found: " + reviewPid);
        }
    }

    private void refreshMarketplaceStats(String targetType, String targetId) {
        if (!MARKETPLACE_STATS_TARGETS.contains(targetType)) {
            return;
        }
        String table = switch (targetType) {
            case "marketplace_plugin" -> "ab_marketplace_plugin";
            case "marketplace_solution" -> "ab_marketplace_solution";
            default -> throw new IllegalStateException("Unexpected marketplace target: " + targetType);
        };
        jdbcTemplate.update(
                """
                UPDATE %s
                   SET average_rating = COALESCE((
                           SELECT ROUND(AVG(rating)::numeric, 2)
                             FROM ab_review
                            WHERE target_type = ? AND target_id = ?
                              AND parent_id IS NULL AND status = 'visible'
                              AND rating IS NOT NULL
                       ), 0),
                       review_count = (
                           SELECT COUNT(*)
                             FROM ab_review
                            WHERE target_type = ? AND target_id = ?
                              AND parent_id IS NULL AND status = 'visible'
                       ),
                       updated_at = NOW()
                 WHERE pid = ?
                """.formatted(table),
                targetType, targetId, targetType, targetId, targetId);
    }

    private ReviewResponse toReviewResponse(Map<String, Object> row) {
        Long currentUserId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        Long userId = longObject(row.get("user_id"));
        return new ReviewResponse(
                stringValue(row.get("pid")),
                stringValue(row.get("target_type")),
                stringValue(row.get("target_id")),
                stringValue(row.get("parent_id")),
                userId,
                stringValue(row.get("user_name")),
                integerObject(row.get("rating")),
                stringValue(row.get("title")),
                stringValue(row.get("content")),
                intValue(row.get("helpful_count")),
                intValue(row.get("reply_count")),
                currentUserId != null && currentUserId.equals(userId),
                instantValue(row.get("created_at")),
                instantValue(row.get("updated_at")));
    }

    private String normalizeTargetType(String targetType) {
        String value = requireText(targetType, "targetType is required");
        return value.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private String normalizeVoteType(String voteType) {
        String value = requireText(voteType, "voteType is required").toLowerCase(Locale.ROOT);
        if (!Set.of("helpful", "report").contains(value)) {
            throw new BusinessException(ResponseCode.BadParam, "Unsupported voteType: " + voteType);
        }
        return value;
    }

    private Integer normalizeRating(Integer rating, boolean topLevel, String targetType) {
        if (!topLevel) {
            return null;
        }
        if (rating == null) {
            if (RATING_REQUIRED_TARGETS.contains(targetType)) {
                throw new BusinessException(ResponseCode.BadParam, "Rating is required for " + targetType);
            }
            return null;
        }
        if (rating < 1 || rating > 5) {
            throw new BusinessException(ResponseCode.BadParam, "Rating must be between 1 and 5");
        }
        return rating;
    }

    private Long requireCurrentUserId() {
        if (!MetaContext.exists() || MetaContext.getCurrentUserId() == null) {
            throw new BusinessException(ResponseCode.UserNotLoginInOrAccessTokenInvalid, "Authentication is required");
        }
        return MetaContext.getCurrentUserId();
    }

    private Long requireCurrentTenantId() {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            throw new BusinessException(ResponseCode.UserNotLoginInOrAccessTokenInvalid, "Tenant context is required");
        }
        return MetaContext.getCurrentTenantId();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResponseCode.BadParam, message);
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value) {
        Number number = numberValue(value);
        return number == null ? 0 : number.intValue();
    }

    private Integer integerObject(Object value) {
        Number number = numberValue(value);
        return number == null ? null : number.intValue();
    }

    private long longValue(Object value) {
        Number number = numberValue(value);
        return number == null ? 0L : number.longValue();
    }

    private Long longObject(Object value) {
        Number number = numberValue(value);
        return number == null ? null : number.longValue();
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Instant instantValue(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return null;
    }

    private record ParentReview(String pid, String rootId, int depth) {
    }
}
