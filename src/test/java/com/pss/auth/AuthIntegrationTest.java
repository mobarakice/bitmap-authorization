package com.pss.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pss.auth.dto.LoginRequest;
import com.pss.auth.dto.LoginResponse;
import com.pss.auth.dto.PermissionCheckRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "logging.level.org.flywaydb=DEBUG",
        "logging.level.org.flywaydb.core=DEBUG",
        "spring.flyway.enabled=true"
    }
)
@Testcontainers
public class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("auth_db")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .withCommand("redis-server --requirepass 1234");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("app.redis.host", redis::getHost);
        registry.add("app.redis.port", redis::getFirstMappedPort);
        registry.add("app.redis.password", () -> "1234");
        registry.add("app.jwt.secret", () -> "c2VjdXJlLWp3dC1zZWNyZXQta2V5LWZvci1yZWRpcy1iYXNlLWF1dGgtc2VydmljZS0yMDI2");
        registry.add("app.jwt.expiry-seconds", () -> "3600");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext).build();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testAdminLoginAndPermissionCheck() throws Exception {
        // 1. Login as admin
        var loginRequest = new LoginRequest("admin", "password123");
        
        var mvcResult = mockMvc.perform(post("/api/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk())
                .andReturn();
                
        var responseString = mvcResult.getResponse().getContentAsString();
        var loginResponse = objectMapper.readValue(responseString, LoginResponse.class);
        var token = loginResponse.accessToken();
        assertNotNull(token);

        // 2. Check permission for a dummy action
        var req = new PermissionCheckRequest("GET", "/api/users");
        
        mockMvc.perform(post("/api/auth/check")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }
}
