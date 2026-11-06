package com.financetracker.unit.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financetracker.controller.advice.ApiExceptionHandler;
import com.financetracker.controller.auth.AuthController;
import com.financetracker.controller.auth.AuthControllerApi;
import com.financetracker.controller.auth.EmailAccountController;
import com.financetracker.controller.auth.EmailAccountControllerApi;
import com.financetracker.controller.sync.RecoveryController;
import com.financetracker.controller.sync.RecoveryControllerApi;
import com.financetracker.controller.sync.SyncController;
import com.financetracker.controller.sync.SyncControllerApi;
import com.financetracker.model.dto.auth.AccessIdentityDto;
import com.financetracker.model.dto.auth.TokenResponseDto;
import com.financetracker.model.dto.auth.ProfileResponseDto;
import com.financetracker.model.dto.sync.PullResponseDto;
import com.financetracker.model.dto.sync.RecoverySessionDto;
import com.financetracker.security.RateLimits;
import com.financetracker.service.auth.AuthService;
import com.financetracker.service.auth.EmailAccountService;
import com.financetracker.service.sync.RecoverySyncService;
import com.financetracker.service.sync.SyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerContractTest {
    private final AuthService auth = mock(AuthService.class);
    private final EmailAccountService accounts = mock(EmailAccountService.class);
    private final SyncService sync = mock(SyncService.class);
    private final RecoverySyncService recovery = mock(RecoverySyncService.class);
    private final RateLimits limits = mock(RateLimits.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID epoch = UUID.randomUUID();
    private final AccessIdentityDto identity = AccessIdentityDto.builder().userId(UUID.randomUUID()).deviceId(UUID.randomUUID()).jti(UUID.randomUUID()).epoch(epoch).build();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @Test
    @DisplayName("OpenAPI documents every operation and schema field")
    void openApiDocumentsOperationsAndFields() throws Exception {
        var contract = mapper.readTree(Path.of("docs/openapi.yaml").toFile());
        int operations = 0;

        for (var path : contract.path("paths")) {
            for (var operation : path) {
                org.junit.jupiter.api.Assertions.assertFalse(operation.path("summary").asText().isBlank());
                org.junit.jupiter.api.Assertions.assertFalse(operation.path("description").asText().isBlank());
                org.junit.jupiter.api.Assertions.assertFalse(operation.path("tags").isEmpty());
                operations++;

                for (var parameter : operation.path("parameters")) {
                    org.junit.jupiter.api.Assertions.assertFalse(parameter.path("description").asText().isBlank());
                }
            }
        }

        assertEquals(17, operations);

        for (var schema : contract.path("components").path("schemas")) {
            org.junit.jupiter.api.Assertions.assertFalse(schema.path("description").asText().isBlank());
            org.junit.jupiter.api.Assertions.assertTrue(schema.has("example"));

            for (var property : schema.path("properties")) {
                org.junit.jupiter.api.Assertions.assertFalse(property.path("description").asText().isBlank());
            }
        }
    }

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthController(auth, limits),
                                        new EmailAccountController(accounts),
                                new SyncController(sync), new RecoveryController(recovery))
                        .defaultRequest(get("/").accept("application/json"))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setValidator(validator)
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                identity, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    @Test
    @DisplayName("Контракты возвращают ResponseEntity, реализации не содержат HTTP-аннотаций")
    void everyEndpointReturnsResponseEntityAndImplementationsHaveNoBindingAnnotations() {
        for (Class<?> api :
                List.of(
                        AuthControllerApi.class,
                        EmailAccountControllerApi.class,
                        SyncControllerApi.class,
                        RecoveryControllerApi.class)) {
            for (var method : api.getDeclaredMethods()) {
                assertEquals(ResponseEntity.class, method.getReturnType(), method.toString());
            }
        }
        for (Class<?> controller :
                List.of(
                        AuthController.class,
                        EmailAccountController.class,
                        SyncController.class,
                        RecoveryController.class)) {
            for (var method : controller.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                for (var parameter : method.getParameters()) {
                    assertEquals(0, parameter.getAnnotations().length, method.toString());
                }
            }
        }
    }

    @Test
    @DisplayName("Регистрация использует контракт интерфейса и возвращает 201")
    void registrationBindsAndValidatesBodyDeclaredOnInterface() throws Exception {
        var device = UUID.randomUUID();
        mvc.perform(
                        post("/api/v1/auth/register")
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "person@example.test",
                                                        "password",
                                                        "valid password",
                                                        "baseCurrencyCode",
                                                        "RUB",
                                                        "deviceId",
                                                        device,
                                                        "inviteCode",
                                                        "invitation"))))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));
        verify(auth).register("person@example.test", "valid password", "RUB", device, "invitation");
    }

    @Test
    @DisplayName("Некорректный DTO регистрации отклоняется до сервиса")
    void invalidAuthBodyIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(auth);
    }

    @Test
    @DisplayName("Почтовые эндпоинты сохраняют статусы 202 и 204")
    void emailEndpointsPreserveAcceptedAndNoContentStatuses() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/email/request")
                                .contentType("application/json")
                                .content("{\"email\":\"person@example.test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(
                        post("/api/v1/auth/password/forgot")
                                .contentType("application/json")
                                .content("{\"email\":\"person@example.test\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(
                        post("/api/v1/auth/email/verify")
                                .contentType("application/json")
                                .content("{\"token\":\"valid-token\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(
                        post("/api/v1/auth/password/reset")
                                .contentType("application/json")
                                .content(
                                        "{\"token\":\"valid-token\",\"password\":\"valid password\"}"))
                .andExpect(status().isNoContent());
        verify(accounts).requestVerification("person@example.test");
        verify(accounts).forgotPassword("person@example.test");
        verify(accounts).verify("valid-token");
        verify(accounts).reset("valid-token", "valid password");
    }

    @Test
    @DisplayName("Valid на интерфейсе запускает проверку email в DTO")
    void emailValidationIsInheritedFromInterface() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/email/request")
                                .contentType("application/json")
                                .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @Test
    @DisplayName("Параметры пагинации и пользователь берутся из интерфейса")
    void paginationDefaultsAndPrincipalAreBoundFromInterface() throws Exception {
        when(sync.pull(identity, 0, 100, epoch.toString()))
                .thenReturn(
                        PullResponseDto.builder()
                                .changes(List.of())
                                .epoch(epoch)
                                .nextCursor(0)
                                .hasMore(false)
                                .build());
        mvc.perform(get("/api/v1/sync/pull").header("X-Recovery-Epoch", epoch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(epoch.toString()));
        verify(sync).pull(identity, 0, 100, epoch.toString());
    }

    @Test
    @DisplayName("Push проверяет вложенные DTO до вызова сервиса")
    void pushValidatesNestedOperationsBeforeService() throws Exception {
        mvc.perform(
                        post("/api/v1/sync/push")
                                .header("X-Recovery-Epoch", epoch)
                                .contentType("application/json")
                                .content("{\"operations\":[{}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sync);
    }

    @Test
    @DisplayName("Recovery связывает заголовок, путь и DTO через интерфейс")
    void recoveryBindsHeaderPathAndBodyAndPreservesNoContent() throws Exception {
        var id = UUID.randomUUID();
        when(recovery.start(identity, epoch.toString()))
                .thenReturn(
                        RecoverySessionDto.builder()
                                .recoveryId(id)
                                .epoch(epoch)
                                .cutoffSeq(0)
                                .deliveredSeq(0)
                                .build());
        mvc.perform(post("/api/v1/sync/recovery/start").header("X-Recovery-Epoch", epoch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryId").value(id.toString()));
        mvc.perform(
                        post("/api/v1/sync/recovery/" + id + "/ack")
                                .header("X-Recovery-Epoch", epoch)
                                .contentType("application/json")
                                .content(
                                        "{\"lastCursor\":0,\"localCopyPreserved\":true,\"pendingOperationsQuarantined\":true}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(recovery).acknowledge(identity, id, 0, epoch.toString());
    }

    @Test
    @DisplayName("Авторизация и профиль сохраняют ответы и пустые статусы")
    void authEndpointsPreserveBodiesAndNoContentStatuses() throws Exception {
        var tokens = TokenResponseDto.builder().accessToken("access").refreshToken("refresh").tokenType("Bearer").expiresIn(900).build();
        when(limits.passwordAttempt(anyString(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        when(auth.login("person@example.test", "valid password", identity.deviceId()))
                .thenReturn(tokens);
        when(auth.refresh("refresh")).thenReturn(tokens);
        when(auth.me(identity))
                .thenReturn(
                        ProfileResponseDto.builder()
                                .id(identity.userId())
                                .email("person@example.test")
                                .baseCurrencyCode("RUB")
                                .build());

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "person@example.test",
                                                        "password",
                                                        "valid password",
                                                        "deviceId",
                                                        identity.deviceId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"));
        mvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType("application/json")
                                .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(identity.userId().toString()));
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(
                        delete("/v1/me")
                                .contentType("application/json")
                                .content("{\"password\":\"valid password\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(auth).logout(identity);
        verify(auth).delete(identity, "valid password");
    }

    @Test
    @DisplayName("Push связывает тело и заголовок из интерфейса и возвращает результаты")
    void pushBindsValidBodyAndReturnsResults() throws Exception {
        when(sync.push(any(), any(), any())).thenReturn(List.of(Map.of("status", "APPLIED")));
        var operation = Map.of("operationId", UUID.randomUUID(), "entityType", "CATEGORY", "entityId", UUID.randomUUID(), "action", "CREATE", "epoch", epoch, "data", Map.of("name", "Food"));
        mvc.perform(
                        post("/api/v1/sync/push")
                                .header("X-Recovery-Epoch", epoch)
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("operations", List.of(operation)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"));
        verify(sync)
                .push(
                        org.mockito.ArgumentMatchers.eq(identity),
                        any(),
                        org.mockito.ArgumentMatchers.eq(epoch.toString()));
    }

    @Test
    @DisplayName("Recovery проверяет ограничения DTO и UUID пути")
    void recoveryRejectsInvalidDtoAndPathBeforeService() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(
                        post("/api/v1/sync/recovery/" + id + "/ack")
                                .header("X-Recovery-Epoch", epoch)
                                .contentType("application/json")
                                .content(
                                        "{\"lastCursor\":0,\"localCopyPreserved\":false,\"pendingOperationsQuarantined\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/sync/recovery/invalid/pull").header("X-Recovery-Epoch", epoch))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recovery);
    }

    @Test
    @DisplayName("Зарегистрированные HTTP-маршруты совпадают с OpenAPI")
    void registeredControllerRoutesMatchOpenApi() throws Exception {
        var context = Objects.requireNonNull(mvc.getDispatcherServlet().getWebApplicationContext());
        var mapping = context.getBean(RequestMappingHandlerMapping.class);
        Set<String> registered = new TreeSet<>();
        mapping.getHandlerMethods()
                .forEach(
                        (route, handler) -> {
                            for (var method : route.getMethodsCondition().getMethods()) {
                                for (String path : route.getPatternValues()) {
                                    registered.add(method.name() + " " + path);
                                }
                            }
                        });

        var paths = mapper.readTree(Path.of("docs/openapi.yaml").toFile()).path("paths");
        Set<String> documented = new TreeSet<>();
        Set<String> methods = Set.of("get", "post", "put", "patch", "delete", "options", "head", "trace");
        for (var path : paths.properties()) {
            if (path.getKey().equals("/actuator/health")) {
                continue; // Actuator is registered by auto-configuration, outside these
                          // controllers.
            }

            for (var operation : path.getValue().properties()) {
                if (methods.contains(operation.getKey())) {
                    documented.add(
                            operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }

        assertEquals(documented, registered);
    }
}
