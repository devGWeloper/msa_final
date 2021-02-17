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

![이벤트 스토밍](https://user-images.githubusercontent.com/53815271/108074344-6e2c1280-70ac-11eb-840f-d9b6611f04e9.png)

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

![마일리지 적립](https://user-images.githubusercontent.com/53815271/108074241-548acb00-70ac-11eb-8020-17b3715a9b47.png)


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

![마일리지 적립 홈화면](https://user-images.githubusercontent.com/53815271/108074244-55236180-70ac-11eb-887c-a7e77b056bed.png)

- 마일리지 적립 취소 후 SirenOrderHomes 화면

![마일리지 취소 홈화면](https://user-images.githubusercontent.com/53815271/108074240-53f23480-70ac-11eb-9873-0fdcf9033a67.png)

위와 같이 마일리지 적립 취소를 하게되면 상태를 Canceled로 바꾼다.

또한 Correlation을 key를 활용하여 orderId를 Key값을 하고 원하는 주문하고 서비스간의 공유가 이루어 졌다.

위 결과로 서로 다른 마이크로 서비스 간에 트랜잭션이 묶여 있음을 알 수 있다.

# 폴리글랏

Shop 서비스의 DB와 Milage 서비스의 DB를 다른 DB를 사용하여 폴리글랏을 만족시키고 있다.

**Shop의 pom.xml DB 설정 코드**

![shop db](https://user-images.githubusercontent.com/53815271/108074776-ed214b00-70ac-11eb-9e58-2e4353905e35.png)

**Milage pom.xml DB 설정 코드**

![마일리지 db](https://user-images.githubusercontent.com/53815271/108074774-ec88b480-70ac-11eb-8327-6a2e1d700e0c.png)

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

![샵 잠시 다운](https://user-images.githubusercontent.com/53815271/108074246-55236180-70ac-11eb-940e-4c51465979f3.png)

- 마일리지 적립취소 요청시 오류 발생

![취소요청 실패화면](https://user-images.githubusercontent.com/53815271/108074249-55bbf800-70ac-11eb-886e-4c3aca6d6a68.png)

- Shop 서비스 재기동 후 정상 동작 확인

![재기동후 취소성공화면](https://user-images.githubusercontent.com/53815271/108074251-55bbf800-70ac-11eb-8e9e-b1eae99943cd.png)

# 운영

# Deploy / Pipeline

- git에서 소스 가져오기

```
git clone https://github.com/devGWeloper/msa_final.git
```

- Build 하기
```
cd /msa_final
cd gateway
mvn package

cd ..
cd sirenorder
mvn package

cd ..
cd payment
mvn package

cd ..
cd shop
mvn package

cd ..
cd sirenorderhome
mvn package

cd ..
cd milage
mvn package
```

- Docker Image Push
```
cd ..
cd SirenOrder
az acr build --registry skuser02 --image skuser02.azurecr.io/sirenorder:v3 .

cd ..
cd Payment
az acr build --registry skuser02 --image skuser02.azurecr.io/payment:v1 .

cd ..
cd Shop
az acr build --registry skuser02 --image skuser02.azurecr.io/shop:v1 .

cd ..
cd Gateway
az acr build --registry skuser02 --image skuser02.azurecr.io/gateway:v1 .

cd ..
cd SirenOrderHome
az acr build --registry skuser02 --image skuser02.azurecr.io/sirenorderhome:v1 .

cd ..
cd Milage
az acr build --registry skuser02 --image skuser02.azurecr.io/milage:v5 .
```
- ACR에서 이미지 가져와서 Kubernetes에서 Deploy하기
```
kubectl create deploy sirenorder --image=skuser02.azurecr.io/sirenorder:v3 -n tutorial
kubectl create deploy payment --image=skuser02.azurecr.io/payment:v1 -n tutorial
kubectl create deploy shop --image=skuser02.azurecr.io/shop:v1 -n tutorial
kubectl create deploy gateway --image=skuser02.azurecr.io/gateway:v1 -n tutorial
kubectl create deploy sirenorderhome --image=skuser02.azurecr.io/sirenorderhome:v1 -n tutorial
kubectl create deploy milage --image=skuser02.azurecr.io/milage:v5 -n tutorial
kubectl get all -n tutorial
```
- Kubectl Deploy 결과 확인
 
![kubectl create](https://user-images.githubusercontent.com/53815271/108164867-f6ee9100-7134-11eb-9e18-27bb24622b69.png)

- Kubernetes에서 서비스 노출 (Docker 생성이기에 Port는 8080이며, Gateway는 LoadBalancer로 생성)
```
kubectl expose deploy sirenorder --type="ClusterIP" --port=8080 -n tutorial
kubectl expose deploy payment --type="ClusterIP" --port=8080 -n tutorial
kubectl expose deploy shop --type="ClusterIP" --port=8080 -n tutorial
kubectl expose deploy gateway --type="LoadBalancer" --port=8080 -n tutorial
kubectl expose deploy sirenorderhome --type="ClusterIP" --port=8080 -n tutorial
kubectl expose deploy milage --type="ClusterIP" --port=8080 -n tutorial
kubectl get all -n tutorial
```

![kubectl expose](https://user-images.githubusercontent.com/53815271/108164873-f81fbe00-7134-11eb-8b57-439ddfc51c38.png)

 # 오토 스케일 아웃
- 오토 스케일을 위해 yml 파일 설정

![autoscale yaml생성](https://user-images.githubusercontent.com/53815271/108164868-f6ee9100-7134-11eb-9117-e8dd827c7199.png)
 
- 오토 스케일 적용 명령어 입력

![오토스케일 명령어 입력 완료](https://user-images.githubusercontent.com/53815271/108164877-f81fbe00-7134-11eb-9e7d-9e7502208c4c.png)

- 시즈 발생 후 오토스케일 결과 확인
```
siege -c100 -t120S -r10 -v --content-type "application/json" 'http://Milage:8080/milages POST {"orderId": 1, "point": 100}'
```

![오토스케일 시즈 결과](https://user-images.githubusercontent.com/53815271/108164881-f950eb00-7134-11eb-9b52-a7f37d383cf5.png)

![오토스케일 되었음](https://user-images.githubusercontent.com/53815271/108164887-fa821800-7134-11eb-9d3e-8e30c0f5c2e4.png)

위와 같이 시즈를 발생시켜 Milage서비스의 pod가 오토 스케일 아웃이 된 것을 확인 할 수 있다.
 
 # 무정지 배포
 - readiness 적용 이전
 
 ![read 주석처리 yaml파일](https://user-images.githubusercontent.com/53815271/108164870-f7872780-7134-11eb-8f77-473bb3c3c150.png)
 
 무정지 배포적용을 하지 않은 Milage 서비스의 yml파일이다
 
 - 무정지 배포 실패 화면
 
![무정지 배포 실패](https://user-images.githubusercontent.com/53815271/108164878-f8b85480-7134-11eb-871e-cdb6cd8c7c83.png)

시즈를 발생시키는 도중 배포를 실행하여 몇개의 요청이 실패된것을 확인할 수 있다.

- readiness 적용

![read 주석 해제 yaml파일](https://user-images.githubusercontent.com/53815271/108164885-f950eb00-7134-11eb-9b52-8b5ea8678b37.png)

다음과 같이 Milage 서비스의 yml파일에서 readiness를 적용하고 apply 시킨 화면이다.

- 무정지 배포 성공

![무정지  배포 성공](https://user-images.githubusercontent.com/53815271/108164864-f655fa80-7134-11eb-8a55-3ceebfc51b1e.png)

시즈를 발생시키는 도중 배포를 실행해도 모든 요청이 성공하여 무정지 배포가 성공적으로 적용된 것을 볼 수 있다.
 
 # Self Healing (Liveness Probe)
 - Liveness Probe 적용 yml파일
 
![live 적용 yaml파일](https://user-images.githubusercontent.com/53815271/108164871-f7872780-7134-11eb-8803-b64be0e67c5a.png)
 
Milage 서비스에 Self Healing을 적용하기 위해 다음과 같이 yml파일을 작성하였다.

- Liveness Probe 결과 화면

![live 적용 결과](https://user-images.githubusercontent.com/53815271/108164880-f8b85480-7134-11eb-9ffe-2fc342e41c86.png)

![live적용 증명](https://user-images.githubusercontent.com/53815271/108164886-f9e98180-7134-11eb-8ae1-9d9d0dbfea4f.png)

위와 같이 Liveness Probe가 적용되어 Milage서비스의 pod가 재생성 되고 있는것을 확인할 수 있다.

# ConfigMap 설정

- Milage 서비스의 application.yml 파일 수정

![config map applcationyaml파일 수정](https://user-images.githubusercontent.com/53815271/108169400-06bda380-713c-11eb-9b65-3a1ef4440618.png)

다음과 같이 Shop 서비스로 동기호출을 할때 configurl로 주었다.

- Milage 서비스 배포를 위한 deployment.yml 파일 수정

![config map deployyml 수정](https://user-images.githubusercontent.com/53815271/108169401-06bda380-713c-11eb-81d5-fd977f1b0e2d.png)

- ConfigMap url 적용
```
kubectl create configmap apiurl --from-literal=url=http://10.0.68.97:8080 -n tutorial
```
![config map 적용 명령어](https://user-images.githubusercontent.com/53815271/108169404-07563a00-713c-11eb-8362-63b83c121805.png)

![config map 적용 명령어 확인](https://user-images.githubusercontent.com/53815271/108169410-07eed080-713c-11eb-8da9-3fd07076854b.png)

- ConfigMap 적용 후 정상 배포 확인

![configmap 정상 동작 확인](https://user-images.githubusercontent.com/53815271/108169398-06250d00-713c-11eb-8c8d-d5e26b95023f.png)

# 써킷 브레이커

- Milage 서비스의 application.yml 파일 수정

![써킷브레이커 application yml 파일](https://user-images.githubusercontent.com/53815271/108169405-07563a00-713c-11eb-914a-88071bf5fc29.png)

다음과 같이 써킷브레이커를 수행하기위해 hystrix를 적용하였다.

- 써킷 브레이커 실패

![써킷 브레이크 실패](https://user-images.githubusercontent.com/53815271/108169397-058c7680-713c-11eb-92ce-9d3eb4ea286b.png)

Milage 서비스를 모두 내리고 다시 배포했음에도 써킷 브레이커가 정상 동작하지 않았다.

코드상에서 부하를 주기위해 Sleep을 주었지만 모두 성공한 화면이다..
