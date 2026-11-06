package com.financetracker.integration.controller.auth;

import java.util.UUID;

import com.financetracker.config.HttpSecurityConfiguration;
import com.financetracker.controller.advice.ApiExceptionHandler;
import com.financetracker.controller.auth.AuthController;
import com.financetracker.exception.AuthFailure;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.entity.sync.RecoveryStateEntity;
import com.financetracker.security.ApiAuthenticationFilter;
import com.financetracker.security.RateLimits;
import com.financetracker.service.auth.AuthService;
import com.financetracker.service.sync.RecoveryState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import java.util.function.Supplier;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({HttpSecurityConfiguration.class, ApiAuthenticationFilter.class, ApiExceptionHandler.class})
class AuthControllerSliceTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @MockitoBean AuthService auth;
    @MockitoBean RateLimits limits;
    @MockitoBean RecoveryState recovery;
    final UUID epoch = UUID.randomUUID();

    @BeforeEach
    void ready() {
        when(recovery.current())
                .thenReturn(RecoveryStateEntity.builder().epoch(epoch).phase("READY").build());
    }

    @Test
    @DisplayName("Регистрация возвращает 201")
    void registrationCreatesAccountWithoutSession() throws Exception {
        var device = UUID.randomUUID();
        mvc.perform(
                        post("/api/v1/auth/register")
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\"person@example.test\",\"password\":\"quiet mountain running river\",\"baseCurrencyCode\":\"RUB\",\"deviceId\":\""
                                                + device
                                                + "\",\"inviteCode\":\"invitation\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));
        verify(auth)
                .register(
                        "person@example.test",
                        "quiet mountain running river",
                        "RUB",
                        device,
                        "invitation");
    }

    @Test
    @DisplayName("Некорректный email не достигает сервиса")
    void invalidEmailStopsBeforeService() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/register")
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\"invalid\",\"password\":\"quiet mountain running river\",\"baseCurrencyCode\":\"RUB\",\"deviceId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"inviteCode\":\"invitation\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test
    @DisplayName("Просроченный JWT закрывает профиль")
    void invalidBearerIsRejected() throws Exception {
        when(auth.authenticate("expired")).thenThrow(AuthFailure.unauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Неизвестный маршрут закрыт")
    void unknownRouteIsDenied() throws Exception {
        mvc.perform(get("/api/v1/unknown")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Recovery блокирует регистрацию")
    void recoveryBlocksRegistration() throws Exception {
        when(recovery.current())
                .thenReturn(RecoveryStateEntity.builder().epoch(epoch).phase("RECOVERY").build());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOVERY_MAINTENANCE"));
        verifyNoInteractions(auth);
    }

    private AccessIdentityDto authenticate() {
        var identity = AccessIdentityDto.builder().userId(UUID.randomUUID()).deviceId(UUID.randomUUID()).jti(UUID.randomUUID()).epoch(epoch).build();
        when(auth.authenticate("access")).thenReturn(identity);
        when(limits.passwordAttempt(anyString(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        return identity;
    }

    @Test
    @DisplayName("Удаление по публичному пути проходит security и вызывает сервис")
    void deleteAccountUsesDocumentedRoute() throws Exception {
        var identity = authenticate();
        mvc.perform(
                        delete("/v1/me")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"password\":\"correct password\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(auth).delete(identity, "correct password");
    }

    @Test
    @DisplayName("Неверный пароль при удалении возвращает безопасный 403")
    void deleteAccountRejectsWrongPassword() throws Exception {
        var identity = authenticate();
        doThrow(AuthFailure.builder().status(403).code("DELETE_REJECTED").build())
                .when(auth)
                .delete(identity, "wrong");
        mvc.perform(
                        delete("/v1/me")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELETE_REJECTED"));
        verify(auth).delete(identity, "wrong");
    }

    @Test
    @DisplayName("Удаление без авторизации не достигает сервиса")
    void deleteAccountRequiresAuthentication() throws Exception {
        mvc.perform(
                        delete("/v1/me")
                                .contentType("application/json")
                                .content("{\"password\":\"correct password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(auth);
    }

    @Test
    @DisplayName("Настройки приложения отключают стандартного in-memory пользователя")
    void applicationConfigurationDoesNotCreateDefaultUser() {
        assertTrue(context.getBeansOfType(UserDetailsService.class).isEmpty());
    }

    @Test
    @DisplayName("Проверка DTO удаления выполняется до сервиса")
    void deleteAccountValidatesRequestBody() throws Exception {
        authenticate();
        mvc.perform(
                        delete("/v1/me")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(auth, never()).delete(any(), anyString());
    }

    @Test
    @DisplayName("Необъявленный путь удаления остаётся закрытым")
    void undocumentedDeleteRouteRemainsDenied() throws Exception {
        authenticate();
        mvc.perform(
                        delete("/api/v1/me")
                                .header("Authorization", "Bearer access")
                                .contentType("application/json")
                                .content("{\"password\":\"correct password\"}"))
                .andExpect(status().isForbidden());
        verify(auth, never()).delete(any(), anyString());
    }
}
