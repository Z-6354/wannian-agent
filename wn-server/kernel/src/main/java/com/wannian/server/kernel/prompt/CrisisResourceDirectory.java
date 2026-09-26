package com.wannian.server.kernel.prompt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 经责任方核验的地区求助资源目录；无有效条目时必须返回空，调用方不得猜号码。 */
@FunctionalInterface
public interface CrisisResourceDirectory {

    /** 只可依据用户明确提供的地区匹配；不得从语言、时区或 IP 推断。 */
    Optional<VerifiedResource> findForExplicitRegion(String userMessage);

    static CrisisResourceDirectory empty() {
        return ignored -> Optional.empty();
    }

    record VerifiedResource(
            String regionCode,
            String displayName,
            String contact,
            Instant verifiedAt,
            String verifiedBy,
            List<String> explicitRegionAliases) {
        public VerifiedResource(
                String regionCode, String displayName, String contact, Instant verifiedAt, String verifiedBy) {
            this(regionCode, displayName, contact, verifiedAt, verifiedBy, List.of(regionCode));
        }

        public VerifiedResource {
            regionCode = required(regionCode, "regionCode");
            displayName = required(displayName, "displayName");
            contact = required(contact, "contact");
            verifiedBy = required(verifiedBy, "verifiedBy");
            explicitRegionAliases = explicitRegionAliases == null ? List.of() : explicitRegionAliases.stream()
                    .map(alias -> required(alias, "explicitRegionAlias")).distinct().toList();
            if (explicitRegionAliases.isEmpty()) {
                throw new IllegalArgumentException("至少需要一个明确地区别名");
            }
            if (verifiedAt == null || verifiedAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("verifiedAt 必须是有效的过去时间");
            }
        }

        /** 运营复核期限：核验记录超过一年即不再用于用户回应。 */
        public boolean isCurrent(Instant now) {
            return !verifiedAt.isBefore(now.minusSeconds(365L * 24 * 60 * 60));
        }

        public Optional<String> explicitlyNamedIn(String userMessage) {
            if (userMessage == null) return Optional.empty();
            String normalized = userMessage.toLowerCase(java.util.Locale.ROOT);
            return explicitRegionAliases.stream().filter(alias -> {
                String candidate = alias.toLowerCase(java.util.Locale.ROOT);
                if (candidate.matches("[a-z0-9-]+")) {
                    return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(candidate) + "(?![a-z0-9])")
                            .matcher(normalized).find();
                }
                return normalized.contains(candidate);
            }).findFirst();
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
            return value.trim();
        }
    }
}
