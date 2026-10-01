package com.idftech.exchangeservice.application.exception;

/**
 * Ресурс не найден: отображается в {@code 404} ProblemDetail.
 *
 * <p>Отдельный тип, потому что «не найден счёт», «не найден лимит» и «не найдена транзакция» —
 * разные ситуации, и клиенту полезно знать, что именно отсутствует.
 */
public class ResourceNotFoundException extends RuntimeException {

  private final String resourceType;
  private final String identifier;

  public ResourceNotFoundException(String resourceType, String identifier) {
    super(resourceType + " not found: " + identifier);
    this.resourceType = resourceType;
    this.identifier = identifier;
  }

  public String getResourceType() {
    return resourceType;
  }

  public String getIdentifier() {
    return identifier;
  }
}
