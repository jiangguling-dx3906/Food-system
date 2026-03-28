package com.example.foodsystem;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.google.gson.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpSession;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Controller
public class FoodController {

    // 1. 【配置区】请确保 API-KEY 和 OSS 信息正确
    private static final String QWEN_API_KEY = "sk-8fbb8c8d82d04bbf9215d821482039fd";

    @Value("${aliyun.oss.endpoint}") private String endpoint;
    @Value("${aliyun.oss.accessKeyId}") private String accessKeyId;
    @Value("${aliyun.oss.accessKeySecret}") private String accessKeySecret;
    @Value("${aliyun.oss.bucketName}") private String bucketName;

    @Autowired private JdbcTemplate jdbcTemplate;

    // --- 页面路由 ---

    @GetMapping("/")
    public String index(HttpSession session, Model model) {
        String user = (String) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        try {
            // 1. 安全查询用户信息
            String userSql = "SELECT use_target, weight, goal_type FROM users WHERE username = ?";
            List<Map<String, Object>> userList = jdbcTemplate.queryForList(userSql, user);

            int useTarget = 0;
            double weight = 65.0;
            String goal = "maintain";

            if (!userList.isEmpty()) {
                Map<String, Object> userData = userList.get(0);
                useTarget = (userData.get("use_target") != null) ? (int)userData.get("use_target") : 0;
                weight = (userData.get("weight") != null) ? (double)userData.get("weight") : 65.0;
                goal = (userData.get("goal_type") != null) ? (String)userData.get("goal_type") : "maintain";
            }

            // 2. 计算目标热量
            int targetCal = (int)(weight * 30);
            if ("lose".equals(goal)) targetCal -= 300;
            if ("gain".equals(goal)) targetCal += 300;

            // 3. 统计今日摄入
            String sqlToday = "SELECT SUM(total_calorie) FROM food_records WHERE username = ? AND DATE(create_time) = CURDATE()";
            Integer consumed = jdbcTemplate.queryForObject(sqlToday, Integer.class, user);
            if (consumed == null) consumed = 0;

            // 4. 塞入数据，确保不为 Null
            model.addAttribute("useTarget", useTarget);
            model.addAttribute("consumed", consumed);
            model.addAttribute("targetCal", targetCal);
            model.addAttribute("percent", Math.min(100, (consumed * 100 / targetCal)));
            model.addAttribute("remaining", Math.max(0, targetCal - consumed));
            model.addAttribute("username", user);

        } catch (Exception e) {
            e.printStackTrace();
            // 极简兜底，防止崩溃
            model.addAttribute("useTarget", 0);
            model.addAttribute("consumed", 0);
        }

        return "index";
    }
    /**
     * 专门给前端轮询用的接口：检查最近是否有新识别记录
     */
    @GetMapping("/api/check-new-record")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> checkNewRecord(HttpSession session) {
        Map<String, Object> res = new HashMap<>();
        String user = (String) session.getAttribute("user");

        if (user == null) {
            res.put("hasNew", false);
            return res;
        }

        try {
            // 查询该用户在过去 10 秒内产生的最新的那条记录 ID
            // 注意：NOW() - INTERVAL 10 SECOND 是 MySQL 语法，代表过去 10 秒
            String sql = "SELECT id FROM food_records WHERE username = ? AND create_time > NOW() - INTERVAL 10 SECOND ORDER BY id DESC LIMIT 1";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, user);

            if (!list.isEmpty()) {
                res.put("hasNew", true);
                res.put("recordId", list.get(0).get("id")); // 告诉前端新纪录的 ID
            } else {
                res.put("hasNew", false);
            }
        } catch (Exception e) {
            res.put("hasNew", false);
        }
        return res;
    }
    @GetMapping("/history")
    public String viewHistory(HttpSession session, Model model) {
        String user = (String) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        try {
            // 1. 查询该用户的所有饮食记录
            String sql = "SELECT * FROM food_records WHERE username = ? ORDER BY id DESC";
            List<Map<String, Object>> records = jdbcTemplate.queryForList(sql, user);

            // 2. 【核心修复】：查询今日已摄入总量（给图表最后一点用）
            String sqlToday = "SELECT SUM(total_calorie) FROM food_records WHERE username = ? AND DATE(create_time) = CURDATE()";
            Integer consumed = jdbcTemplate.queryForObject(sqlToday, Integer.class, user);
            if (consumed == null) consumed = 0;

            // 3. 把所有变量整齐地交给网页
            model.addAttribute("username", user);
            model.addAttribute("records", records);
            model.addAttribute("consumed", consumed); // 就是缺了它！

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("records", new java.util.ArrayList<>());
            model.addAttribute("consumed", 0);
        }

        return "history";
    }


    // --- 核心业务接口 ---

    /**
     * 入口 1：网页版识别（返回 HTML 结果页）
     */
    @PostMapping("/api/identify/web")
    public String identifyFoodWeb(@RequestParam("file") MultipartFile file, Model model, HttpSession session) {
        String currentUser = (String) session.getAttribute("user");
        if (currentUser == null) return "redirect:/login";

        Map<String, Object> result = processImageAndAI(file, currentUser);

        model.addAttribute("username", currentUser);
        model.addAttribute("isFood", result.get("isFood"));
        model.addAttribute("dishName", result.get("name"));
        model.addAttribute("calorie", result.get("calorie"));
        model.addAttribute("advice", result.get("advice"));
        model.addAttribute("weight", result.get("weight"));
        model.addAttribute("totalCal", result.get("totalCal"));
        model.addAttribute("protein", result.get("protein"));
        model.addAttribute("fat", result.get("fat"));
        model.addAttribute("carbs", result.get("carbs"));
        model.addAttribute("imageUrl", result.get("imageUrl"));
        return "result";
    }
    @PostMapping("/api/user/toggle-target")
    public String toggleTarget(HttpSession session) {
        // 这里写更新 users 表 use_target 字段的 SQL
        return "redirect:/";
    }
    /**
     * 入口 2：硬件眼镜版识别（返回纯 JSON 数据）
     */
    @PostMapping("/api/identify/device")
    @org.springframework.web.bind.annotation.ResponseBody // 明确写全名，防止重名冲突
    public Map<String, Object> identifyForDevice(@RequestParam("file") MultipartFile file, @RequestParam("username") String username) {
        return processImageAndAI(file, username);
    }

    // --- 内部逻辑封装（核心大脑） ---

    private Map<String, Object> processImageAndAI(MultipartFile file, String username) {
        Map<String, Object> finalResult = new HashMap<>();
        try {
            // 1. 上传图片到 OSS
            String fileName = UUID.randomUUID() + ".jpg";
            OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            try (InputStream is = file.getInputStream()) {
                ossClient.putObject(bucketName, fileName, is);
            }
            String imageUrl = "https://" + bucketName + "." + endpoint + "/" + fileName;
            ossClient.shutdown();

            // 2. 调用通义千问大模型
            String base64Image = Base64.getEncoder().encodeToString(file.getBytes());
            String smartPrompt = "你是一位资深的营养精算师。任务：识别图片中物体的属性。\n" +
                    "1. 如果是食物：isFood设为true，给出名称、每100g热量(perCal)、根据餐具估算总重量(weight)、蛋白质(protein)、脂肪(fat)、碳水(carbs)以及100字内饮食建议。\n" +
                    "2. 如果非食物：isFood设为false，准确给出名称，其他热量营养填0，建议改为趣味吐槽。严禁返回null。格式：{\"isFood\":true/false, \"name\":\"名\", \"perCal\":数字, \"weight\":数字, \"protein\":\"数字\", \"fat\":\"数字\", \"carbs\":\"数字\", \"advice\":\"建议\"}";

            JsonObject payload = new JsonObject();
            payload.addProperty("model", "qwen-vl-plus");
            JsonObject input = new JsonObject();
            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject imgP = new JsonObject(); imgP.addProperty("image", "data:image/jpeg;base64," + base64Image);
            JsonObject txtP = new JsonObject(); txtP.addProperty("text", smartPrompt);
            content.add(imgP); content.add(txtP);
            message.add("content", content);
            messages.add(message);
            input.add("messages", messages);
            payload.add("input", input);

            OkHttpClient client = new OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
            okhttp3.RequestBody body = okhttp3.RequestBody.create(payload.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder().url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation").addHeader("Authorization", "Bearer " + QWEN_API_KEY).post(body).build();

            try (Response response = client.newCall(request).execute()) {
                String raw = response.body().string();
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                String resultText = root.getAsJsonObject("output").getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                resultText = resultText.replaceAll("(?s)```json|```", "").trim();
                JsonObject foodData = JsonParser.parseString(resultText).getAsJsonObject();

                // 3. 安全解析结果（防JsonNull）
                boolean isFood = safeGetBoolean(foodData, "isFood", false);
                String name = safeGetString(foodData, "name", "未知物体");
                String adv = safeGetString(foodData, "advice", "AI分析中");
                int weight = safeGetInt(foodData, "weight", 200);
                int perCal = safeGetInt(foodData, "perCal", 100);
                int totalCal = (perCal * weight) / 100;
                String protein = safeGetString(foodData, "protein", "0");
                String fat = safeGetString(foodData, "fat", "0");
                String carbs = safeGetString(foodData, "carbs", "0");

                // 4. 如果是食物，存入 MySQL
                if (isFood) {
                    jdbcTemplate.update("INSERT INTO food_records (username, food_name, calorie, total_calorie, estimated_weight, protein, fat, carbs, advice, image_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            username, name, String.valueOf(perCal), totalCal, weight, protein, fat, carbs, adv, imageUrl);
                }

                // 5. 组装返回数据
                finalResult.put("status", "success");
                finalResult.put("isFood", isFood);
                finalResult.put("name", name);
                finalResult.put("calorie", perCal);
                finalResult.put("weight", weight);
                finalResult.put("totalCal", totalCal);
                finalResult.put("protein", protein);
                finalResult.put("fat", fat);
                finalResult.put("carbs", carbs);
                finalResult.put("advice", adv);
                finalResult.put("imageUrl", imageUrl);
            }
        } catch (Exception e) {
            e.printStackTrace();
            finalResult.put("status", "error");
            finalResult.put("name", "处理失败");
            finalResult.put("advice", e.getMessage());
        }
        return finalResult;
    }

    // --- 稳如泰山的工具方法 ---
    private String safeGetString(JsonObject obj, String key, String defaultValue) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : defaultValue;
    }
    private boolean safeGetBoolean(JsonObject obj, String key, boolean defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsBoolean(); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }
    private int safeGetInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsInt(); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }
}