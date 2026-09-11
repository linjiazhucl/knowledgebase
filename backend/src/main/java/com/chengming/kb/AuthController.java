package com.chengming.kb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final long sessionTtlHours;

    public AuthController(JdbcTemplate jdbc, StringRedisTemplate redis,
                          @Value("${app.session-ttl-hours:8}") long sessionTtlHours) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.sessionTtlHours = sessionTtlHours;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, username, password_hash, nickname, role, status FROM kb_user WHERE username = ?", username);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        Map<String, Object> row = users.getFirst();
        String expected = String.valueOf(row.get("password_hash"));
        if (!"active".equals(row.get("status")) || !expected.equals(sha256(password)) || !"admin".equals(row.get("role"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("kb:session:" + token, username, Duration.ofHours(sessionTtlHours));
        jdbc.update("UPDATE kb_user SET last_active_at = CURRENT_TIMESTAMP WHERE id = ?", row.get("id"));
        Map<String, Object> user = new HashMap<>();
        user.put("id", row.get("id"));
        user.put("username", row.get("username"));
        user.put("nickname", row.get("nickname"));
        user.put("role", row.get("role"));
        user.put("token", token);
        return Map.of("data", user);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        Map<String, Object> user = new HashMap<>(currentUser(token));
        user.remove("password_hash");
        return Map.of("data", user);
    }

    @PutMapping("/me")
    public Map<String, Object> updateMe(@RequestHeader("X-Admin-Token") String token,
                                         @RequestBody Map<String, String> body) {
        Map<String, Object> user = currentUser(token);
        String nickname = body.getOrDefault("nickname", "").trim();
        if (nickname.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "昵称不能为空");
        jdbc.update("UPDATE kb_user SET nickname = ? WHERE id = ?", nickname, user.get("id"));
        return Map.of("data", Map.of("message", "个人信息已保存", "nickname", nickname));
    }

    @PutMapping("/password")
    public Map<String, Object> updatePassword(@RequestHeader("X-Admin-Token") String token,
                                               @RequestBody Map<String, String> body) {
        Map<String, Object> user = currentUser(token);
        String currentPassword = body.getOrDefault("currentPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");
        String confirmPassword = body.getOrDefault("confirmPassword", "");
        if (!sha256(currentPassword).equals(String.valueOf(user.get("password_hash")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (newPassword.length() < 8 || !newPassword.equals(confirmPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 8 位且两次输入必须一致");
        }
        jdbc.update("UPDATE kb_user SET password_hash = ? WHERE id = ?", sha256(newPassword), user.get("id"));
        redis.delete("kb:session:" + token);
        return Map.of("data", Map.of("message", "密码已更新，请重新登录"));
    }

    private Map<String, Object> currentUser(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, username, password_hash, nickname, role, status FROM kb_user WHERE username = ?",
                redis.opsForValue().get("kb:session:" + token));
        if (users.isEmpty() || !"active".equals(users.getFirst().get("status"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录态已失效");
        }
        return users.getFirst();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算密码摘要", exception);
        }
    }
}
