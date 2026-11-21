import {createGenericPaymentClient, mountStripePaymentAttempt, mountStripeSubscriptionMethod} from '@generic-payments/sdk';
import {loadStripe} from '@stripe/stripe-js/pure/index.js';
import {installFailedRenewalPage} from './billing-failed-renewal-page';
import './billing-first-payment.css';

// This dedicated test page configures the loader before either SDK mount can use it.
loadStripe.setLoadParameters({advancedFraudSignals: false});
installFailedRenewalPage({createGenericPaymentClient, mountStripePaymentAttempt, mountStripeSubscriptionMethod});
