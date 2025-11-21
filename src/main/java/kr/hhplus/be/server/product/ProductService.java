package kr.hhplus.be.server.product;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Transactional
    public Product getProduct(Long productId)
    {
        return productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
    }

    @Transactional
    public List<Product> getProducts(Long[] productIds)
    {
        return productRepository.findAllById(List.of(productIds));
    }

    @Transactional
    public List<Product> getProducts()
    {
        return productRepository.findAll();
    }

    @Transactional
    public Product createProduct(Product product)
    {
        return productRepository.save(product);
    }

    @Transactional
    public Product charge(Long productId, Integer amount)
    {
        var product = productRepository.findForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        product.charge(amount);
        return productRepository.save(product);
    }

    @Transactional
    public Product debit(Long productId, Integer amount)
    {
        var product = productRepository.findForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        product.debit(amount);
        return productRepository.save(product);
    }
}
