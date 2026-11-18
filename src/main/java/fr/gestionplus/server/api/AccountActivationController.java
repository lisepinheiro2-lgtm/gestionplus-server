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

	@PostMapping("/api/invitations/{invitationID}/sms-challenge")
	public void requestActivationChallenge(@PathVariable UUID invitationID, @RequestBody SmsChallengeRequest request) {

		activationService.requestActivationChallenge(invitationID, request.token());

	}

	record SmsChallengeRequest(String token) {
	}
}
