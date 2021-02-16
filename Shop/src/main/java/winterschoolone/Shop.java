package winterschoolone;

import javax.persistence.*;
import org.springframework.beans.BeanUtils;
import java.util.List;

@Entity
@Table(name="Shop_table")
public class Shop {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;
    private Long orderId;
    private String userId;
    private String menuId;
    private Integer qty;
    private Integer point;

    @PostPersist
    public void onPostPersist(){

        if(this.getPoint() == null || this.getPoint() == 0) {
            Assigned assigned = new Assigned();
            BeanUtils.copyProperties(this, assigned);
            assigned.publishAfterCommit();
        } else {
            MilageSaved milageSaved = new MilageSaved();
            BeanUtils.copyProperties(this, milageSaved);
            milageSaved.publishAfterCommit();

            try {
                Thread.currentThread().sleep((long) (400 + Math.random() * 220));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

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
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
    public String getMenuId() {
        return menuId;
    }

    public void setMenuId(String menuId) {
        this.menuId = menuId;
    }
    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public Integer getPoint() {
        return point;
    }

    public void setPoint(Integer point) {
        this.point = point;
    }


}
