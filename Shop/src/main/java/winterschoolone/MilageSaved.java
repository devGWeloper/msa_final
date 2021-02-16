package winterschoolone;

public class MilageSaved extends AbstractEvent {

    private Long id;
    private Long sirenOrderId;
    private String status;

    public MilageSaved(){
        super();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
    public Long getSirenOrderId() {
        return sirenOrderId;
    }

    public void setSirenOrderId(Long sirenOrderId) {
        this.sirenOrderId = sirenOrderId;
    }
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}