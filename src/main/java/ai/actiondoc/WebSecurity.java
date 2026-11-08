package ai.actiondoc;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class WebSecurity extends OncePerRequestFilter {
    private final String desktopToken=System.getenv().getOrDefault("ACTIONDOC_DESKTOP_TOKEN","");
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","same-origin");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if(!desktopToken.isBlank()){
            String supplied=request.getHeader("X-ActionDoc-Desktop");
            if(supplied==null||!java.security.MessageDigest.isEqual(desktopToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))){response.sendError(403);return;}
        }
        String host=request.getServerName();
        if (!host.equals("localhost")&&!host.equals("127.0.0.1")&&!host.equals("::1")) { response.sendError(403); return; }
        if (request.getRequestURI().startsWith("/api/")&&!request.getMethod().equals("GET")) {
            HttpSession session=request.getSession(false);
            if(session==null || session.getAttribute("csrf")==null || !session.getAttribute("csrf").equals(request.getHeader("X-CSRF-Token"))) {
                response.setStatus(403); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"error\":\"세션을 새로고침한 뒤 다시 시도해 주세요.\"}"); return;
            }
        }
        chain.doFilter(request,response);
    }
}
