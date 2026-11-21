package fr.gestionplus.server.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.account.AccountActivationService;

@RestController
public class AccountActivationController {

	private AccountActivationService activationService;

	AccountActivationController(AccountActivationService activationService) {
		this.activationService = activationService;
	}

	@PostMapping(AccountActivationRoutes.SMS_CHALLENGE_PATH)
	public void requestActivationChallenge(@PathVariable UUID invitationID, @RequestBody SmsChallengeRequest request) {

		activationService.requestActivationChallenge(invitationID, request.token());

	}

	@PostMapping(AccountActivationRoutes.ACTIVATION_PATH)
	public void requestActivation(@PathVariable UUID invitationID, @RequestBody Activation request) {
		activationService.completeActivation(invitationID, request.token(), request.proof(), request.userName(),
				request.password());
	}

	record Activation(String token, String proof, String userName, String password) {
	}

	record SmsChallengeRequest(String token) {
	}
}
