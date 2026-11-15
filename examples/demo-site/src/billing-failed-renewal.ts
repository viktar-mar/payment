import {createGenericPaymentClient, mountStripePaymentAttempt, mountStripeSubscriptionMethod} from '@generic-payments/sdk';
import {installFailedRenewalPage} from './billing-failed-renewal-page';
import './billing-first-payment.css';

installFailedRenewalPage({createGenericPaymentClient, mountStripePaymentAttempt, mountStripeSubscriptionMethod});
