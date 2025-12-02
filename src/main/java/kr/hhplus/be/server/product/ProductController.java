package kr.hhplus.be.server.product;

import kr.hhplus.be.server.product.DTO.ProductResponse;
import kr.hhplus.be.server.product.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    @Autowired
    private  ProductService productService;


    @GetMapping("/top-selling")
    public ResponseEntity<List<ProductResponse>> getTopSellingProducts() {
        // Service에 위임하고 결과를 HTTP 200 OK와 함께 반환
        List<ProductResponse> topProducts = productService.getTopSellingProducts();
        return ResponseEntity.ok(topProducts);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProductResponse(@PathVariable Long productId){
        return ResponseEntity.ok(productService.getProduct(productId));
    }

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getProducts() {
        // Service에 위임하여 모든 상품 목록을 조회
        List<ProductResponse> products = productService.getProducts();
        return ResponseEntity.ok(products);
    }
}