package com.example.foodsystem;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@Controller
public class UserController {

    @Autowired private JdbcTemplate jdbcTemplate;

    // 1. 去登录页面
    @GetMapping("/login")
    public String loginPage() { return "login"; }

    // 2. 处理登录逻辑
    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password, HttpSession session, Model model) {
        String sql = "SELECT * FROM users WHERE username = ? AND password = ?";
        List<Map<String, Object>> users = jdbcTemplate.queryForList(sql, username, password);

        if (!users.isEmpty()) {
            session.setAttribute("user", username); // 关键：把名字存进Session，代表登录成功
            return "redirect:/"; // 登录成功跳到首页
        } else {
            model.addAttribute("error", "用户名或密码错误");
            return "login";
        }
    }

    // 3. 处理注册逻辑
    @PostMapping("/register")
    public String register(@RequestParam String username, @RequestParam String password, Model model) {
        try {
            jdbcTemplate.update("INSERT INTO users (username, password) VALUES (?, ?)", username, password);
            model.addAttribute("msg", "注册成功，请登录");
            return "login";
        } catch (Exception e) {
            model.addAttribute("error", "用户名已存在");
            return "login";
        }
    }

    // 4. 退出登录
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate(); // 销毁Session
        return "redirect:/login";
    }
    @GetMapping("/delete-record/{id}")
    public String deleteRecord(@PathVariable Long id, HttpSession session) {
        if (session.getAttribute("user") != null) {
            jdbcTemplate.update("DELETE FROM food_records WHERE id = ?", id);
        }
        return "redirect:/history";
    }
}