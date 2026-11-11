package fr.gestionplus.server.api;

import java.io.IOException;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
class ApiErrorController implements ErrorController {

	@RequestMapping("${spring.web.error.path:${error.path:/error}}")
	void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
		Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		int status = value instanceof Integer code && code >= 400 && code < 600 ? code : 500;
		HttpApiError.fromStatus(status).write(response, status);
	}
}
