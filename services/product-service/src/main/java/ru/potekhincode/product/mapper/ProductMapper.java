package ru.potekhincode.product.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import ru.potekhincode.product.dto.request.CreateProductRequest;
import ru.potekhincode.product.dto.request.UpdateProductRequest;
import ru.potekhincode.product.dto.response.ProductResponse;
import ru.potekhincode.product.model.Product;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductResponse toResponse(Product product);

    Product toEntity(CreateProductRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(UpdateProductRequest request,@MappingTarget Product product);
}
