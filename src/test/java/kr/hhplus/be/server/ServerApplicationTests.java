package kr.hhplus.be.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class ServerApplicationTests {
	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	void contextLoads() {
		redisTemplate.opsForValue().set("hello", "redis");
		String value = redisTemplate.opsForValue().get("hello");
		System.out.println(value); // redis
	}

}
