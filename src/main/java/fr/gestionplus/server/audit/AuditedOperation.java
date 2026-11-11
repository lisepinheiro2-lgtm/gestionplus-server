package fr.gestionplus.server.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedOperation {

	AuditAction value();

	int account() default -1;

	int structure() default -1;

	int tenant() default -1;

	int employee() default -1;

	int invitation() default -1;
}
