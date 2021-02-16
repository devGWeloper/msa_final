
package winterschoolone.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Date;

@FeignClient(name="Shop", url="${api.url.Shop}")
public interface ShopService {

    @RequestMapping(method= RequestMethod.GET, path="/shops")
    public void milageRemove(@RequestBody Shop shop);

}