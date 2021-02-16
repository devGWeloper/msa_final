# msa_final
# 서비스 시나리오
### 기능적 요구사항
1. 고객이 음료를 주문한다.
2. 고객이 결제를 한다.
3. 결제가 완료되면 주문내역을 매장으로 보낸다.
4. 매장에서 주문을 할당한다. 
5. 고객이 주문을 취소할 수 있다.
6. 관리자는 주문번호에 대해 마일리지를 부여할 수 있다.
7. 관리자는 주문번호에 대해 부여한 마일리지를 취소할 수 있다.
8. 고객이 중간중간 주문상태 및 마일리지 적립 현황을 조회한다.


### 비기능적 요구사항
1. 트랜잭션
    1. 결제가 되지않으면 주문이 진행되지 않는다 → Sync 호출
    2. Shop 서비스가 장애가 발생하면 마일리지 적립이 되지 않는다 → Sync 호출 
1. 장애격리
    1. 결제시스템에서 장애가 발생해도 주문취소는 24시간 받을 수 있어야 한다 → Async (event-driven), Eventual Consistency
    2. 매장시스템에서 장애가 발생해도 마일리지 적립 요청을 항상 받을 수 있어야 한다 → Async (event-driven), Eventual Consistency
    3. 마일리지 요청이 많아 매장시스템이 과중되면 잠시 마일리지 요청을 받지않고 잠시후에 하도록 유도한다 → Circuit breaker, fallback
1. 성능
    1. 고객이 주문상태를 SirenOrderHome에서 확인 할 수 있어야 한다. → CQRS 

# Event Storming 결과

# 구현
분석/설계 단계에서 도출된 헥사고날 아키텍처에 따라, 구현한 각 서비스를 로컬에서 실행하는 방법은 아래와 같다 (각자의 포트넘버는 8081 ~ 8085, 8088 이다)
```
cd SirenOrder
mvn spring-boot:run  

cd Payment
mvn spring-boot:run

cd SirenOrderHome
mvn spring-boot:run 

cd Shop
mvn spring-boot:run  

cd gateway
mvn spring-boot:run 

cd Milage
mvn spring-boot:run 
```

## DDD 의 적용
msaez.io 를 통해 구현한 Aggregate 단위로 Entity 를 선언 후, 구현을 진행하였다.

Entity Pattern 과 Repository Pattern 을 적용하기 위해 Spring Data REST 의 RestRepository 를 적용하였다.

**Milage 서비스의 Milage.java**

```java 
package winterschoolone;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import winterschoolone.external.Shop;
import winterschoolone.external.ShopService;

import java.util.List;

@Entity
@Table(name="Milage_table")
public class Milage {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long orderId;
    private Integer point;

    @PrePersist
    public void onPrePersist(){
        Saved saved = new Saved();
        BeanUtils.copyProperties(this, saved);
        saved.publishAfterCommit();


    }

    @PreRemove
    public void onPreRemove(){
        Canceled canceled = new Canceled();
        BeanUtils.copyProperties(this, canceled);
        canceled.publishAfterCommit();

        //Following code causes dependency to external APIs
        // it is NOT A GOOD PRACTICE. instead, Event-Policy mapping is recommended.

        Shop shop = new Shop();
        // mappings goes here
        shop.setOrderId(this.getOrderId());
        shop.setPoint(-1);
        MilageApplication.applicationContext.getBean(ShopService.class)
            .milageRemove(shop);

    }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }
    public Integer getPoint() {
        return point;
    }

    public void setPoint(Integer point) {
        this.point = point;
    }




}

```

**Shop 서비스의 PolicyHandler.java**
```java
package winterschoolone;

import winterschoolone.config.kafka.KafkaProcessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class PolicyHandler{
    @StreamListener(KafkaProcessor.INPUT)
    public void onStringEventListener(@Payload String eventString){

    }
    
    @Autowired
    ShopRepository shopRepository;

    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverPayed_(@Payload Payed payed){

    	if(payed.isMe()){
            System.out.println("##### listener  : " + payed.toJson());
            
            Shop shop = new Shop();
            shop.setMenuId(payed.getMenuId());
            shop.setOrderId(payed.getOrderId());
            shop.setQty(payed.getQty());
            shop.setUserId(payed.getUserId());
            
            shopRepository.save(shop);
        }
    }
    @StreamListener(KafkaProcessor.INPUT)
    public void wheneverSaved_(@Payload Saved saved){

        if(saved.isMe()){
            System.out.println("##### listener  : " + saved.toJson());

            Shop shop = new Shop();
            shop.setPoint(saved.getPoint());
            shop.setOrderId(saved.getOrderId());
            shopRepository.save(shop);
        }
    }

}

```

- DDD 적용 후 REST API의 테스트를 통하여 정상적으로 동작하는 것을 확인할 수 있었다.  
  
- 마일리지 적립 후 동작 결과



# GateWay 적용
API GateWay를 통하여 마이크로 서비스들의 집입점을 통일할 수 있다.
다음과 같이 GateWay를 적용하였다.

```yaml
server:
  port: 8088

---

spring:
  profiles: default
  cloud:
    gateway:
      routes:
        - id: SirenOrder
          uri: http://localhost:8081
          predicates:
            - Path=/sirenOrders/** 
        - id: Payment
          uri: http://localhost:8082
          predicates:
            - Path=/payments/** 
        - id: Shop
          uri: http://localhost:8083
          predicates:
            - Path=/shops/** 
        - id: SirenOrderHome
          uri: http://localhost:8084
          predicates:
            - Path= /sirenOrderHomes/**
        - id: Milage
          uri: http://localhost:8085
          predicates:
            - Path= /milages/**
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "*"
            allowedMethods:
              - "*"
            allowedHeaders:
              - "*"
            allowCredentials: true


---

spring:
  profiles: docker
  cloud:
    gateway:
      routes:
        - id: SirenOrder
          uri: http://SirenOrder:8080
          predicates:
            - Path=/sirenOrders/** 
        - id: Payment
          uri: http://Payment:8080
          predicates:
            - Path=/payments/** 
        - id: Shop
          uri: http://Shop:8080
          predicates:
            - Path=/shops/** 
        - id: SirenOrderHome
          uri: http://SirenOrderHome:8080
          predicates:
            - Path= /sirenOrderHomes/**
        - id: Milage
          uri: http://Milage:8080
          predicates:
            - Path= /milages/**
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "*"
            allowedMethods:
              - "*"
            allowedHeaders:
              - "*"
            allowCredentials: true

server:
  port: 8080

```

# CQRS
Materialized View 를 구현하여, 타 마이크로서비스의 데이터 원본에 접근없이(Composite 서비스나 조인SQL 등 없이) 도 내 서비스의 화면 구성과 잦은 조회가 가능하게 구현해 두었다.
본 프로젝트에서 View 역할은 SirenOrderHomes 서비스가 수행한다.

- 마일리지 적립 실행 후 SirenOrderHomes 화면



- 마일리지 적립 취소 후 SirenOrderHomes 화면



위와 같이 마일리지 적립 취소를 하게되면 Point를 -1로 Set하여 Point를 무효화 시킨다.

또한 Correlation을 key를 활용하여 orderId를 Key값을 하고 원하는 주문하고 서비스간의 공유가 이루어 졌다.

위 결과로 서로 다른 마이크로 서비스 간에 트랜잭션이 묶여 있음을 알 수 있다.

# 폴리글랏

Shop 서비스의 DB와 Milage 서비스의 DB를 다른 DB를 사용하여 폴리글랏을 만족시키고 있다.

**Shop의 pom.xml DB 설정 코드**



**Milage pom.xml DB 설정 코드**



# 동기식 호출 과 Fallback 처리

분석단계에서의 조건 중 하나로 마일리지(canceled)->매장(milageRemove) 간의 호출은 동기식 일관성을 유지하는 트랜잭션으로 처리하기로 하였다. 호출 프로토콜은 Rest Repository 에 의해 노출되어있는 REST 서비스를 FeignClient 를 이용하여 호출하도록 한다.

**Milage 서비스 내 external.PaymentService**
```java
@FeignClient(name="Shop", url="${api.url.Shop}")
public interface ShopService {

    @RequestMapping(method= RequestMethod.GET, path="/shops")
    public void milageRemove(@RequestBody Shop shop);

}
```

**동작 확인**
- Shop 서비스 잠시 내림



- 마일리지 적립취소 요청시 오류 발생



- Shop 서비스 재기동 후 정상 동작 확인



# 운영
.
