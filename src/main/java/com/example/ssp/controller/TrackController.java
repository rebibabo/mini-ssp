package com.example.ssp.controller;

import com.example.ssp.service.TrackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

@Slf4j
@Tag(name = "埋点追踪", description = "广告曝光与点击的追踪回调，凭 requestId 关联竞价结果")
@RestController
@RequestMapping("/api/v1/track")
@RequiredArgsConstructor
public class TrackController {

    private final TrackService trackService;

    /**
     * 曝光追踪：广告展示时 App 调用此接口
     * 返回 204 No Content，不需要响应体
     */
    @Operation(summary = "曝光追踪", description = "广告展示时调用，记录曝光事件，返回 204 无响应体")
    @GetMapping("/impression")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    /**
     *  rid: requestId
      {
        "impressionTrackUrl": "/api/v1/track/impression?rid=req-123",
        "clickTrackUrl": "/api/v1/track/click?rid=req-123"
      }
     */
    public void impression(@RequestParam String rid, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        trackService.handleImpression(rid, ip, ua);
    }

    /**
     * 点击追踪：用户点击广告时 App 调用此接口
     * 返回 302 重定向到广告落地页
     */
    @Operation(summary = "点击追踪", description = "用户点击广告时调用，记录点击事件并 302 重定向到落地页")
    @GetMapping("/click")
    public RedirectView click(@RequestParam String rid, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");

        String clickUrl = trackService.handleClick(rid, ip, ua);

        if (clickUrl == null) {
            // requestId 无效或已过期，重定向到默认页
            return new RedirectView("/");
        }

        // 跳转到广告页
        return new RedirectView(clickUrl);
    }
}
