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
        
        try {
            Thread.currentThread().sleep((long) (400 + Math.random() * 220));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

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
